package io.urlshortener.redirectservice.integrationtest.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.SetSubscriptionAttributesRequest;
import software.amazon.awssdk.services.sns.model.SubscribeRequest;
import software.amazon.awssdk.services.sns.model.SubscribeResponse;
import software.amazon.awssdk.services.sns.model.UnsubscribeRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.CreateQueueResponse;
import software.amazon.awssdk.services.sqs.model.DeleteQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.net.http.HttpClient;
import java.util.UUID;

/**
 * Wires the shared {@link io.urlshortener.redirectservice.integrationtest.RedirectLifecycleIntegrationTest
 * RedirectLifecycleIntegrationTest} to talk to already-deployed {@code redirect-service} and {@code url-service}
 * instances over real HTTP, and to a throwaway SQS queue subscribed to the real, already-provisioned {@code
 * click-events} SNS topic (see {@code url-shortener-infra}'s {@code sns-sqs} Terraform module) so the test
 * can still assert a {@code ClickEvent} was actually published.
 *
 * <p>
 *     Active only under the {@code deployed} profile<br/>
 *     {@link LocalEnvironmentConfig} supplies the equivalent beans for the {@code local} profile.
 * </p>
 */
@Configuration
@Profile("deployed")
public class DeployedEnvironmentConfig {

	@Bean
	public RestClient redirectApiClient(@Value("${integration-test.base-url}") final String baseUrl) {
		// Redirects must not be auto-followed: the test asserts on the 302 response itself (status,
		// Location header), not on whatever the destination URL happens to return.
		final HttpClient httpClient = HttpClient.newBuilder()
				.followRedirects(HttpClient.Redirect.NEVER)
				.build();
		return RestClient.builder()
				.baseUrl(baseUrl)
				.requestFactory(new JdkClientHttpRequestFactory(httpClient))
				.build();
	}

	@Bean
	public RestClient linksApiClient(@Value("${integration-test.url-service-base-url}") final String baseUrl) {
		return RestClient.builder()
				.baseUrl(baseUrl)
				.build();
	}

	@Bean(destroyMethod = "close")
	public SqsClient clickEventsSqsClient() {
		return SqsClient.create();
	}

	/**
	 * Creates a throwaway SQS queue subscribed to the real {@code click-events} topic, and tears down
	 * both the queue and the subscription once the test run finishes.
	 *
	 * @param sqsClient    the SQS client to create the queue with.
	 * @param clickEventsTopicArn the ARN of the already-provisioned {@code click-events} SNS topic.
	 * @return the URL of the created throwaway queue.
	 */
	@Bean
	public String clickEventsQueueUrl(final SqsClient sqsClient,
									  @Value("${integration-test.click-events-topic-arn}") final String clickEventsTopicArn) {
		try (SnsClient snsClient = SnsClient.create()) {
			final CreateQueueResponse queue = sqsClient.createQueue(CreateQueueRequest.builder()
					.queueName("redirect-service-it-" + UUID.randomUUID())
					.build());
			final String queueArn = sqsClient.getQueueAttributes(GetQueueAttributesRequest.builder()
							.queueUrl(queue.queueUrl())
							.attributeNames(QueueAttributeName.QUEUE_ARN)
							.build())
					.attributes()
					.get(QueueAttributeName.QUEUE_ARN);
			final SubscribeResponse subscription = snsClient.subscribe(SubscribeRequest.builder()
					.topicArn(clickEventsTopicArn)
					.protocol("sqs")
					.endpoint(queueArn)
					.build());
			snsClient.setSubscriptionAttributes(SetSubscriptionAttributesRequest.builder()
					.subscriptionArn(subscription.subscriptionArn())
					.attributeName("RawMessageDelivery")
					.attributeValue("true")
					.build());
			Runtime.getRuntime().addShutdownHook(
					new Thread(() -> teardownOnExit(sqsClient, queue.queueUrl(), subscription.subscriptionArn()))
			);
			return queue.queueUrl();
		}
	}

	private void teardownOnExit(final SqsClient sqsClient, final String queueUrl, final String subscriptionArn) {
		try (SnsClient snsClient = SnsClient.create()) {
			final UnsubscribeRequest unsubscribeRequest = UnsubscribeRequest.builder()
					.subscriptionArn(subscriptionArn)
					.build();
			snsClient.unsubscribe(unsubscribeRequest);
		} finally {
			final DeleteQueueRequest deleteQueueRequest = DeleteQueueRequest.builder()
					.queueUrl(queueUrl)
					.build();
			sqsClient.deleteQueue(deleteQueueRequest);
		}
	}

}
