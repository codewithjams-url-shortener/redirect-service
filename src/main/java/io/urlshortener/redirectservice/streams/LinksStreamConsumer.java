package io.urlshortener.redirectservice.streams;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.urlshortener.redirectservice.constant.AwsConstants;
import io.urlshortener.redirectservice.model.cache.CachedLink;
import io.urlshortener.redirectservice.property.AwsProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.dynamodb.model.Record;
import software.amazon.awssdk.services.dynamodb.streams.DynamoDbStreamsClient;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
public class LinksStreamConsumer {

	private static final Duration POLL_INTERVAL = Duration.ofSeconds(1);

	private static final int SHARD_DISCOVERY_INTERVAL_CYCLES = 60; // ~once a minute at a 1s poll interval

	private final DynamoDbClient dynamoDbClient;

	private final DynamoDbStreamsClient dynamoDbStreamsClient;

	private final RedisTemplate<String, CachedLink> redisTemplate;

	private final AwsProperties awsProperties;

	private final Counter consumerErrorCounter;

	private final Counter invalidationCounter;

	private final AtomicReference<Instant> lastSuccessfulPoll;

	/**
	 * Package-private, rather than private, so unit tests can drive the polling logic directly without
	 * going through {@link #start()}'s background thread.
	 */
	final Map<String, String> shardIterators;

	String streamArn;

	private ExecutorService executorService;

	public LinksStreamConsumer(final DynamoDbClient dynamoDbClient, final DynamoDbStreamsClient dynamoDbStreamsClient,
							   final RedisTemplate<String, CachedLink> redisTemplate, final AwsProperties awsProperties,
							   final MeterRegistry meterRegistry) {
		this.dynamoDbClient = dynamoDbClient;
		this.dynamoDbStreamsClient = dynamoDbStreamsClient;
		this.redisTemplate = redisTemplate;
		this.awsProperties = awsProperties;
		this.consumerErrorCounter = Counter.builder("streams.consumer.errors")
				.description("Number of consumer errors when performing GetRecords or DescribeStream")
				.register(meterRegistry);
		this.invalidationCounter = Counter.builder("streams.consumer.invalidations")
				.description("Number of invalidations per record")
				.register(meterRegistry);
		this.lastSuccessfulPoll = new AtomicReference<>(Instant.now());
		Gauge.builder(
				"streams.consumer.lag.seconds",
						lastSuccessfulPoll,
						ref -> Duration.between(ref.get(), Instant.now()).getSeconds()
				)
				.description("Seconds since the Links DynamoDB Stream consumer last completed a successful poll")
				.register(meterRegistry);
		this.shardIterators = new HashMap<>();
	}

	@PostConstruct
	public void start() {
		try {
			streamArn = resolveStreamArn();
			discoverShards();
			executorService = Executors.newSingleThreadExecutor();
			executorService.submit(this::poll);
		} catch (Exception e) {
			consumerErrorCounter.increment();
			log.atError()
					.setCause(e)
					.log("Failed to start Links DynamoDB Stream consumer - cache invalidation will rely on the "
							+ "TTL safety net until this is resolved and the service is restarted");
		}
	}

	@PreDestroy
	public void stop() {
		if (Objects.nonNull(executorService)) {
			executorService.shutdownNow();
			final boolean terminated;
			try {
				terminated = executorService.awaitTermination(10, TimeUnit.SECONDS);
				log.atDebug()
						.addKeyValue("terminated", terminated)
						.log("Executor Service terminated");
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}

	String resolveStreamArn() {
		final String tableName = awsProperties.getDynamoDb()
				.getTables()
				.get(AwsConstants.TABLE_LINKS);
		final DescribeTableRequest request = DescribeTableRequest.builder()
				.tableName(tableName)
				.build();
		final DescribeTableResponse response = dynamoDbClient.describeTable(request);
		final String arn = response.table().latestStreamArn();
		log.atInfo()
				.addKeyValue("tableName", tableName)
				.addKeyValue("streamArn", arn)
				.log("Resolved Links table stream ARN");
		return arn;
	}

	void discoverShards() {
		final DescribeStreamRequest request = DescribeStreamRequest.builder()
				.streamArn(streamArn)
				.build();
		final DescribeStreamResponse response = dynamoDbStreamsClient.describeStream(request);
		for (final Shard shard : response.streamDescription().shards()) {
			shardIterators.computeIfAbsent(shard.shardId(), this::fetchLatestShardIterator);
		}
	}

	private String fetchLatestShardIterator(final String shardId) {
		final GetShardIteratorRequest request = GetShardIteratorRequest.builder()
				.streamArn(streamArn)
				.shardId(shardId)
				.shardIteratorType(ShardIteratorType.LATEST)
				.build();
		final GetShardIteratorResponse response = dynamoDbStreamsClient.getShardIterator(request);
		return response.shardIterator();
	}

	private void poll() {
		int cyclesSinceLastDiscovery = 0;
		while (!Thread.currentThread().isInterrupted()) {
			try {
				pollAllShards();
				lastSuccessfulPoll.set(Instant.now());
				cyclesSinceLastDiscovery++;
				if (cyclesSinceLastDiscovery >= SHARD_DISCOVERY_INTERVAL_CYCLES) {
					discoverShards();
					cyclesSinceLastDiscovery = 0;
				}
			} catch (Exception e) {
				consumerErrorCounter.increment();
				log.atWarn()
						.setCause(e)
						.log("Error while polling Links DynamoDB Stream");
			}
			sleep();
		}
	}

	void pollAllShards() {
		final Iterator<Map.Entry<String, String>> iterator = shardIterators.entrySet().iterator();
		while (iterator.hasNext()) {
			final Map.Entry<String, String> entry = iterator.next();
			try {
				final String nextIterator = pollShard(entry.getValue());
				if (Objects.isNull(nextIterator)) {
					iterator.remove(); // shard closed and fully drained
				} else {
					entry.setValue(nextIterator);
				}
			} catch (Exception e) {
				consumerErrorCounter.increment();
				log.atWarn()
						.setCause(e)
						.addKeyValue("shardId", entry.getKey())
						.log("Error polling shard, dropping it - will be rediscovered if still open");
				iterator.remove();
			}
		}
	}

	private String pollShard(final String shardIterator) {
		final GetRecordsRequest request = GetRecordsRequest.builder()
				.shardIterator(shardIterator)
				.limit(100)
				.build();
		final GetRecordsResponse response = dynamoDbStreamsClient.getRecords(request);
		response.records().forEach(this::invalidate);
		return response.nextShardIterator();
	}

	void invalidate(final Record record) {
		final String shortCode = record.dynamodb().keys().get("shortCode").s();
		final String key = "link:%s".formatted(shortCode);
		redisTemplate.delete(key);
		invalidationCounter.increment();
		log.atDebug()
				.addKeyValue("shortCode", shortCode)
				.addKeyValue("eventName", record.eventNameAsString())
				.log("Invalidated cache entry from stream record");
	}

	private void sleep() {
		try {
			Thread.sleep(POLL_INTERVAL.toMillis());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

}
