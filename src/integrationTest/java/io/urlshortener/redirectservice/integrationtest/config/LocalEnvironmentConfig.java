package io.urlshortener.redirectservice.integrationtest.config;

import io.floci.testcontainers.FlociContainer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.StreamSpecification;
import software.amazon.awssdk.services.dynamodb.model.StreamViewType;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.CreateTopicRequest;
import software.amazon.awssdk.services.sns.model.CreateTopicResponse;
import software.amazon.awssdk.services.sns.model.SetSubscriptionAttributesRequest;
import software.amazon.awssdk.services.sns.model.SubscribeRequest;
import software.amazon.awssdk.services.sns.model.SubscribeResponse;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.CreateQueueResponse;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;

/**
 * Wires the shared {@link io.urlshortener.redirectservice.integrationtest.RedirectLifecycleIntegrationTest
 * RedirectLifecycleIntegrationTest} to real redirect-service and url-service instances, each launched as a
 * genuine separate OS process (the built boot jars, not in-process {@code @SpringBootTest} contexts)
 * against a shared Testcontainers-managed floci instance (DynamoDB, SNS, SQS) and a Testcontainers-managed
 * Redis instance.
 *
 * <p>
 *     {@code url-service} is started only because {@code redirect-service} has no write endpoint of its own - the test
 *     uses {@code url-service}'s real {@code POST}/{@code DELETE} {@code /links} to create and remove the rows it
 *     redirects against, rather than writing directly into DynamoDB.
 * </p>
 *
 * <p>
 *     A throwaway SQS queue is created and subscribed to the {@code click-events} SNS topic (with raw message delivery,
 *     so the queue body is the {@code ClickEvent} JSON directly) so the test can assert a {@code ClickEvent} was
 *     actually published for a given redirect attempt.
 * </p>
 *
 * <p>
 *     Active only under the {@code local} profile<br/>
 *     {@link DeployedEnvironmentConfig} supplies the equivalent beans for the {@code deployed} profile.
 * </p>
 *
 * <p>
 *     Tunable constants are read from {@code application.yaml} via {@link LocalTestSettings} rather than hardcoded -
 *     see its Javadoc for why that reads the file standalone instead of through {@code @Value}.
 * </p>
 */
@Configuration
@Profile("local")
public class LocalEnvironmentConfig {

	private static final FlociContainer floci = new FlociContainer();

	private static final GenericContainer<?> redis =
			new GenericContainer<>(DockerImageName.parse(LocalTestSettings.redisImage()))
					.withExposedPorts(6379)
					.waitingFor(Wait.forListeningPort());

	private static final String clickEventsTopicArn;

	private static final String createdClickEventsQueueUrl;

	static {
		floci.start();
		redis.start();
		createLinksTable();
		clickEventsTopicArn = createClickEventsTopic();
		createdClickEventsQueueUrl = createClickEventsQueueSubscribedToTopic(clickEventsTopicArn);
	}

	private static void createLinksTable() {
		try (DynamoDbClient client = buildDynamoDbClient()) {
			final KeySchemaElement schemaElement = KeySchemaElement.builder()
					.attributeName(LocalTestSettings.linksTablePartitionKey())
					.keyType(KeyType.HASH)
					.build();
			final AttributeDefinition attributeDefinition = AttributeDefinition.builder()
					.attributeName(LocalTestSettings.linksTablePartitionKey())
					.attributeType(ScalarAttributeType.S)
					.build();
			client.createTable(CreateTableRequest.builder()
					.tableName(LocalTestSettings.linksTableName())
					.keySchema(schemaElement)
					.attributeDefinitions(attributeDefinition)
					.billingMode(BillingMode.PAY_PER_REQUEST)
					// NEW_AND_OLD_IMAGES to match production (see ADR-0003/ADR-0012) - redirect-service's own
					// LinksStreamConsumer needs a real stream to consume from.
					.streamSpecification(StreamSpecification.builder()
							.streamEnabled(true)
							.streamViewType(StreamViewType.NEW_AND_OLD_IMAGES)
							.build())
					.build());
		}
	}

	private static String createClickEventsTopic() {
		try (SnsClient client = buildSnsClient()) {
			final CreateTopicRequest createTopicRequest = CreateTopicRequest.builder()
					.name(LocalTestSettings.clickEventsTopicName())
					.build();
			final CreateTopicResponse response = client.createTopic(createTopicRequest);
			return response.topicArn();
		}
	}

	private static String createClickEventsQueueSubscribedToTopic(final String topicArn) {
		try (SqsClient sqsClient = buildSqsClient(); SnsClient snsClient = buildSnsClient()) {
			final CreateQueueRequest createQueueRequest = CreateQueueRequest.builder()
					.queueName(LocalTestSettings.clickEventsQueueName())
					.build();
			final CreateQueueResponse queue = sqsClient.createQueue(createQueueRequest);
			final GetQueueAttributesRequest getQueueAttributesRequest = GetQueueAttributesRequest.builder()
					.queueUrl(queue.queueUrl())
					.attributeNames(QueueAttributeName.QUEUE_ARN)
					.build();
			final String queueArn = sqsClient.getQueueAttributes(getQueueAttributesRequest)
					.attributes()
					.get(QueueAttributeName.QUEUE_ARN);
			final SubscribeRequest subscribeRequest = SubscribeRequest.builder()
					.topicArn(topicArn)
					.protocol("sqs")
					.endpoint(queueArn)
					.build();
			final SubscribeResponse subscription = snsClient.subscribe(subscribeRequest);
			// Raw delivery so the queue body is the ClickEvent JSON directly, rather than wrapped in an
			// SNS notification envelope - simpler for the test to assert against.
			snsClient.setSubscriptionAttributes(SetSubscriptionAttributesRequest.builder()
					.subscriptionArn(subscription.subscriptionArn())
					.attributeName("RawMessageDelivery")
					.attributeValue("true")
					.build());
			return queue.queueUrl();
		}
	}

	private static DynamoDbClient buildDynamoDbClient() {
		return DynamoDbClient.builder()
				.endpointOverride(URI.create(floci.getEndpoint()))
				.region(Region.of(floci.getRegion()))
				.credentialsProvider(credentialsProvider())
				.build();
	}

	private static SnsClient buildSnsClient() {
		return SnsClient.builder()
				.endpointOverride(URI.create(floci.getEndpoint()))
				.region(Region.of(floci.getRegion()))
				.credentialsProvider(credentialsProvider())
				.build();
	}

	private static SqsClient buildSqsClient() {
		return SqsClient.builder()
				.endpointOverride(URI.create(floci.getEndpoint()))
				.region(Region.of(floci.getRegion()))
				.credentialsProvider(credentialsProvider())
				.build();
	}

	private static StaticCredentialsProvider credentialsProvider() {
		return StaticCredentialsProvider.create(
				AwsBasicCredentials.create(floci.getAccessKey(), floci.getSecretKey()));
	}

	@Bean(destroyMethod = "shutdown")
	@Qualifier("redirectServiceInstance")
	public LocalServiceInstance redirectServiceInstance(
			@Value("${integration-test.app-jar}") final String appJarPath
	) throws IOException {
		final int port = findFreePort();
		final ProcessBuilder processBuilder = new ProcessBuilder(
				javaBinary(),
				"-Dserver.port=%s".formatted(port),
				"-Daws.dynamodb.endpoint-override=%s".formatted(floci.getEndpoint()),
				"-Daws.sns.endpoint-override=%s".formatted(floci.getEndpoint()),
				"-Daws.region=%s".formatted(floci.getRegion()),
				"-Daws.credential.access-key=%s".formatted(floci.getAccessKey()),
				"-Daws.credential.secret-key=%s".formatted(floci.getSecretKey()),
				"-Daws.dynamodb.tables.links-table=%s".formatted(LocalTestSettings.linksTableName()),
				"-Daws.sns.topics.click-events-topic=%s".formatted(clickEventsTopicArn),
				"-Dspring.data.redis.host=%s".formatted(redis.getHost()),
				"-Dspring.data.redis.port=%s".formatted(redis.getMappedPort(6379)),
				"-jar", appJarPath
		);
		final Process process = start(processBuilder);
		waitUntilHealthy(port, process);
		return new LocalServiceInstance(process, port);
	}

	@Bean(destroyMethod = "shutdown")
	@Qualifier("urlServiceInstance")
	public LocalServiceInstance urlServiceInstance(
			@Value("${integration-test.url-service-app-jar}") final String appJarPath
	) throws IOException {
		final int port = findFreePort();
		final ProcessBuilder processBuilder = new ProcessBuilder(
				javaBinary(),
				"-Dserver.port=" + port,
				"-Daws.dynamodb.endpoint-override=" + floci.getEndpoint(),
				"-Daws.region=" + floci.getRegion(),
				"-Daws.credential.access-key=" + floci.getAccessKey(),
				"-Daws.credential.secret-key=" + floci.getSecretKey(),
				"-Daws.dynamodb.tables.links-table=" + LocalTestSettings.linksTableName(),
				"-jar", appJarPath
		);
		final Process process = start(processBuilder);
		waitUntilHealthy(port, process);
		return new LocalServiceInstance(process, port);
	}

	@Bean
	public RestClient redirectApiClient(@Qualifier("redirectServiceInstance") final LocalServiceInstance instance) {
		return RestClient.builder()
				.baseUrl("http://localhost:" + instance.port())
				.requestFactory(nonFollowingRequestFactory())
				.build();
	}

	@Bean
	public RestClient linksApiClient(@Qualifier("urlServiceInstance") final LocalServiceInstance instance) {
		return RestClient.builder()
				.baseUrl("http://localhost:" + instance.port())
				.build();
	}

	@Bean
	public SqsClient clickEventsSqsClient() {
		return buildSqsClient();
	}

	@Bean
	public String clickEventsQueueUrl() {
		return createdClickEventsQueueUrl;
	}

	private static JdkClientHttpRequestFactory nonFollowingRequestFactory() {
		// Redirects must not be auto-followed: the test asserts on the 302 response itself (status,
		// Location header), not on whatever the destination URL happens to return.
		return new JdkClientHttpRequestFactory(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build());
	}

	private static String javaBinary() {
		return System.getProperty("java.home") + "/bin/java";
	}

	private static Process start(final ProcessBuilder processBuilder) throws IOException {
		processBuilder.redirectErrorStream(true);
		processBuilder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
		return processBuilder.start();
	}

	private static int findFreePort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

	private static void waitUntilHealthy(final int port, final Process process) {
		final RestClient healthClient = RestClient.create();
		final Duration timeout = LocalTestSettings.healthCheckTimeout();
		final Instant deadline = Instant.now().plus(timeout);
		while (Instant.now().isBefore(deadline)) {
			if (!process.isAlive()) {
				throw new IllegalStateException(
						"Process exited before becoming healthy, exit code %s".formatted(process.exitValue()));
			}
			try {
				healthClient.get()
						.uri("http://localhost:" + port + "/actuator/health")
						.retrieve()
						.toBodilessEntity();
				return;
			} catch (RestClientException e) {
				sleep();
			}
		}
		throw new IllegalStateException("Process did not become healthy within %s".formatted(timeout));
	}

	private static void sleep() {
		try {
			Thread.sleep(LocalTestSettings.healthCheckPollInterval().toMillis());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}

}
