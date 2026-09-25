package io.urlshortener.redirectservice.streams;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.urlshortener.redirectservice.constant.AwsConstants;
import io.urlshortener.redirectservice.model.cache.CachedLink;
import io.urlshortener.redirectservice.property.AwsProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DescribeStreamRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeStreamResponse;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableResponse;
import software.amazon.awssdk.services.dynamodb.model.GetRecordsRequest;
import software.amazon.awssdk.services.dynamodb.model.GetRecordsResponse;
import software.amazon.awssdk.services.dynamodb.model.GetShardIteratorRequest;
import software.amazon.awssdk.services.dynamodb.model.GetShardIteratorResponse;
import software.amazon.awssdk.services.dynamodb.model.Record;
import software.amazon.awssdk.services.dynamodb.model.Shard;
import software.amazon.awssdk.services.dynamodb.model.StreamDescription;
import software.amazon.awssdk.services.dynamodb.model.StreamRecord;
import software.amazon.awssdk.services.dynamodb.model.TableDescription;
import software.amazon.awssdk.services.dynamodb.streams.DynamoDbStreamsClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class LinksStreamConsumerTest {

	private static final String STREAM_ARN = "arn:aws:dynamodb:us-west-2:000000000000:table/Links/stream/2026-01-01T00:00:00.000";

	private static final String SHARD_ID = "shard-1";

	@Mock
	private DynamoDbClient dynamoDbClient;

	@Mock
	private DynamoDbStreamsClient dynamoDbStreamsClient;

	@Mock
	private RedisTemplate<String, CachedLink> redisTemplate;

	private SimpleMeterRegistry meterRegistry;

	private LinksStreamConsumer consumer;

	@BeforeEach
	void setUp() {
		final AwsProperties.DynamoDb dynamoDb = new AwsProperties.DynamoDb();
		dynamoDb.setTables(Map.of(AwsConstants.TABLE_LINKS, "Links"));
		final AwsProperties awsProperties = new AwsProperties();
		awsProperties.setDynamoDb(dynamoDb);

		meterRegistry = new SimpleMeterRegistry();
		consumer = new LinksStreamConsumer(dynamoDbClient, dynamoDbStreamsClient, redisTemplate, awsProperties,
				meterRegistry);
		consumer.streamArn = STREAM_ARN;
	}

	private double errorCount() {
		return meterRegistry.counter("streams.consumer.errors").count();
	}

	private double invalidationCount() {
		return meterRegistry.counter("streams.consumer.invalidations").count();
	}

	@Test
	void start_shouldNotThrowAndShouldIncrementErrorCounter_whenResolveStreamArnFails() {
		// Arrange
		given(dynamoDbClient.describeTable(any(DescribeTableRequest.class)))
				.willThrow(new RuntimeException("DynamoDB unreachable"));

		// Act & Assert
		assertThatCode(() -> consumer.start()).doesNotThrowAnyException();
		assertThat(errorCount()).isEqualTo(1.0);
	}

	@Test
	void resolveStreamArn_shouldReturnLatestStreamArn_whenTableIsDescribed() {
		// Arrange
		final DescribeTableResponse response = DescribeTableResponse.builder()
				.table(TableDescription.builder().latestStreamArn(STREAM_ARN).build())
				.build();
		given(dynamoDbClient.describeTable(any(DescribeTableRequest.class)))
				.willReturn(response);

		// Act
		final String result = consumer.resolveStreamArn();

		// Assert
		assertThat(result).isEqualTo(STREAM_ARN);
	}

	@Test
	void discoverShards_shouldAddShardIterator_whenShardIsNotYetTracked() {
		// Arrange
		final DescribeStreamResponse describeStreamResponse = DescribeStreamResponse.builder()
				.streamDescription(StreamDescription.builder()
						.shards(Shard.builder().shardId(SHARD_ID).build())
						.build())
				.build();
		given(dynamoDbStreamsClient.describeStream(any(DescribeStreamRequest.class)))
				.willReturn(describeStreamResponse);
		given(dynamoDbStreamsClient.getShardIterator(any(GetShardIteratorRequest.class)))
				.willReturn(GetShardIteratorResponse.builder().shardIterator("iterator-1").build());

		// Act
		consumer.discoverShards();

		// Assert
		assertThat(consumer.shardIterators).containsEntry(SHARD_ID, "iterator-1");
	}

	@Test
	void discoverShards_shouldNotOverwriteExistingIterator_whenShardIsAlreadyTracked() {
		// Arrange
		consumer.shardIterators.put(SHARD_ID, "existing-iterator");
		final DescribeStreamResponse describeStreamResponse = DescribeStreamResponse.builder()
				.streamDescription(StreamDescription.builder()
						.shards(Shard.builder().shardId(SHARD_ID).build())
						.build())
				.build();
		given(dynamoDbStreamsClient.describeStream(any(DescribeStreamRequest.class)))
				.willReturn(describeStreamResponse);

		// Act
		consumer.discoverShards();

		// Assert
		assertThat(consumer.shardIterators).containsEntry(SHARD_ID, "existing-iterator");
		verify(dynamoDbStreamsClient, never())
				.getShardIterator(any(GetShardIteratorRequest.class));
	}

	@Test
	void pollAllShards_shouldInvalidateCacheAndAdvanceIterator_whenRecordsArePresent() {
		// Arrange
		consumer.shardIterators.put(SHARD_ID, "iterator-1");
		final Record record = Record.builder()
				.eventName("MODIFY")
				.dynamodb(StreamRecord.builder()
						.keys(Map.of("shortCode", AttributeValue.builder().s("abc1234").build()))
						.build())
				.build();
		final GetRecordsResponse response = GetRecordsResponse.builder()
				.records(record)
				.nextShardIterator("iterator-2")
				.build();
		given(dynamoDbStreamsClient.getRecords(any(GetRecordsRequest.class)))
				.willReturn(response);

		// Act
		consumer.pollAllShards();

		// Assert
		verify(redisTemplate).delete("link:abc1234");
		assertThat(consumer.shardIterators).containsEntry(SHARD_ID, "iterator-2");
		assertThat(invalidationCount()).isEqualTo(1.0);
	}

	@Test
	void pollAllShards_shouldRemoveShard_whenNextShardIteratorIsNull() {
		// Arrange
		consumer.shardIterators.put(SHARD_ID, "iterator-1");
		final GetRecordsResponse response = GetRecordsResponse.builder()
				.records(List.of())
				.nextShardIterator((String) null)
				.build();
		given(dynamoDbStreamsClient.getRecords(any(GetRecordsRequest.class)))
				.willReturn(response);

		// Act
		consumer.pollAllShards();

		// Assert
		assertThat(consumer.shardIterators).doesNotContainKey(SHARD_ID);
	}

	@Test
	void pollAllShards_shouldRemoveShardAndIncrementErrorCounter_whenGetRecordsThrows() {
		// Arrange
		consumer.shardIterators.put(SHARD_ID, "iterator-1");
		given(dynamoDbStreamsClient.getRecords(any(GetRecordsRequest.class)))
				.willThrow(new RuntimeException("boom"));

		// Act
		consumer.pollAllShards();

		// Assert
		assertThat(consumer.shardIterators).doesNotContainKey(SHARD_ID);
		assertThat(errorCount()).isEqualTo(1.0);
	}

	@Test
	void invalidate_shouldDeleteCorrectRedisKeyAndIncrementCounter_whenRecordHasShortCode() {
		// Arrange
		final Record record = Record.builder()
				.eventName("REMOVE")
				.dynamodb(StreamRecord.builder()
						.keys(Map.of("shortCode", AttributeValue.builder().s("xyz789").build()))
						.build())
				.build();

		// Act
		consumer.invalidate(record);

		// Assert
		verify(redisTemplate).delete("link:xyz789");
		assertThat(invalidationCount()).isEqualTo(1.0);
	}

}
