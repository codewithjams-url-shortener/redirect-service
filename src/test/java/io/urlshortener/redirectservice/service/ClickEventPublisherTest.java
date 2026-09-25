package io.urlshortener.redirectservice.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.urlshortener.eventcontracts.ClickOutcome;
import io.urlshortener.redirectservice.constant.AwsConstants;
import io.urlshortener.redirectservice.property.AwsProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.sns.SnsAsyncClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.PublishResponse;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ClickEventPublisherTest {

	private static final String SHORT_CODE = "abc1234";

	private static final String TOPIC_ARN = "arn:aws:sns:us-east-1:000000000000:click-events";

	private static final String REFERER_DOMAIN = "referrer.com";

	private static final String USER_AGENT_RAW = "Mozilla/5.0";

	private static final String IP_HASH = "hashedIp";

	@Mock
	private SnsAsyncClient snsAsyncClient;

	private ClickEventPublisher clickEventPublisher;

	@BeforeEach
	void setUp() {
		final AwsProperties.Sns sns = new AwsProperties.Sns();
		sns.setTopics(Map.of(AwsConstants.TOPIC_CLICK_EVENTS, TOPIC_ARN));
		final AwsProperties awsProperties = new AwsProperties();
		awsProperties.setSns(sns);

		clickEventPublisher = new ClickEventPublisher(snsAsyncClient, awsProperties, new ObjectMapper());
	}

	@Test
	void publish_shouldPublishSerializedEventToConfiguredTopic_whenSerializationSucceeds() {
		// Arrange
		given(snsAsyncClient.publish(any(PublishRequest.class)))
				.willReturn(CompletableFuture.completedFuture(PublishResponse.builder().build()));

		// Act
		clickEventPublisher.publish(SHORT_CODE, ClickOutcome.RESOLVED, REFERER_DOMAIN, USER_AGENT_RAW, IP_HASH);

		// Assert
		final ArgumentCaptor<PublishRequest> requestCaptor = ArgumentCaptor.forClass(PublishRequest.class);
		verify(snsAsyncClient).publish(requestCaptor.capture());
		final PublishRequest publishRequest = requestCaptor.getValue();
		assertThat(publishRequest.topicArn()).isEqualTo(TOPIC_ARN);
		assertThat(publishRequest.message())
				.contains("\"shortCode\":\"" + SHORT_CODE + "\"")
				.contains("\"outcome\":\"RESOLVED\"")
				.contains("\"refererDomain\":\"" + REFERER_DOMAIN + "\"")
				.contains("\"userAgentRaw\":\"" + USER_AGENT_RAW + "\"")
				.contains("\"ipHash\":\"" + IP_HASH + "\"");
	}

	@Test
	void publish_shouldNotCallSnsClient_whenEventFailsToSerialize() throws JsonProcessingException {
		// Arrange
		final AwsProperties.Sns sns = new AwsProperties.Sns();
		sns.setTopics(Map.of(AwsConstants.TOPIC_CLICK_EVENTS, TOPIC_ARN));
		final AwsProperties awsProperties = new AwsProperties();
		awsProperties.setSns(sns);
		final ObjectMapper failingObjectMapper = mock(ObjectMapper.class);
		willThrow(new JsonMappingException(null, "boom")).given(failingObjectMapper).writeValueAsString(any());
		clickEventPublisher = new ClickEventPublisher(snsAsyncClient, awsProperties, failingObjectMapper);

		// Act
		clickEventPublisher.publish(SHORT_CODE, ClickOutcome.RESOLVED, REFERER_DOMAIN, USER_AGENT_RAW, IP_HASH);

		// Assert
		verify(snsAsyncClient, never()).publish(any(PublishRequest.class));
	}

	@Test
	void publish_shouldNotThrow_whenSnsPublishFails() {
		// Arrange
		given(snsAsyncClient.publish(any(PublishRequest.class)))
				.willReturn(CompletableFuture.failedFuture(new RuntimeException("SNS unavailable")));

		// Act & Assert
		clickEventPublisher.publish(SHORT_CODE, ClickOutcome.RESOLVED, REFERER_DOMAIN, USER_AGENT_RAW, IP_HASH);
	}

}
