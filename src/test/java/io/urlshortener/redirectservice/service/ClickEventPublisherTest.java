package io.urlshortener.redirectservice.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.urlshortener.eventcontracts.ClickOutcome;
import io.urlshortener.redirectservice.constant.AwsConstants;
import io.urlshortener.redirectservice.property.AwsProperties;
import io.urlshortener.servicecommon.MessageAttributeKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.sns.SnsAsyncClient;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.PublishResponse;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

	private OtelTracingFixture tracing;

	@BeforeEach
	void setUp() {
		tracing = new OtelTracingFixture();
		final AwsProperties.Sns sns = new AwsProperties.Sns();
		sns.setTopics(Map.of(AwsConstants.TOPIC_CLICK_EVENTS, TOPIC_ARN));
		final AwsProperties awsProperties = new AwsProperties();
		awsProperties.setSns(sns);

		clickEventPublisher = new ClickEventPublisher(snsAsyncClient, awsProperties, new ObjectMapper(), tracing.tracer(),
				tracing.propagator());
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
		clickEventPublisher = new ClickEventPublisher(snsAsyncClient, awsProperties, failingObjectMapper, tracing.tracer(),
				tracing.propagator());

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

	@Test
	void publish_shouldSendAW3CTraceParentOfThePublishSpan_whenPublishing() {
		// Arrange
		givenPublishSucceeds();

		// Act
		clickEventPublisher.publish(SHORT_CODE, ClickOutcome.RESOLVED, REFERER_DOMAIN, USER_AGENT_RAW, IP_HASH);

		// Assert
		final SpanData publishSpan = onlySpan();
		final MessageAttributeValue traceParent = publishedRequest().messageAttributes()
				.get(MessageAttributeKeys.TRACE_PARENT);
		assertThat(traceParent.dataType()).isEqualTo("String");
		// The consumer's span is parented to the publish span, so it is the publish span's own context that travels.
		assertThat(traceParent.stringValue())
				.matches("00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]")
				.contains(publishSpan.getTraceId())
				.contains(publishSpan.getSpanId());
	}

	@Test
	void publish_shouldUseTheTraceIdAsTheCorrelationId_whenPublishing() {
		// Arrange
		givenPublishSucceeds();

		// Act
		clickEventPublisher.publish(SHORT_CODE, ClickOutcome.RESOLVED, REFERER_DOMAIN, USER_AGENT_RAW, IP_HASH);

		// Assert
		assertThat(publishedRequest().messageAttributes().get(MessageAttributeKeys.CORRELATION_ID).stringValue())
				.isEqualTo(onlySpan().getTraceId());
	}

	@Test
	void publish_shouldLeaveTraceStateOut_whenThereIsNone() {
		// Arrange
		givenPublishSucceeds();

		// Act
		clickEventPublisher.publish(SHORT_CODE, ClickOutcome.RESOLVED, REFERER_DOMAIN, USER_AGENT_RAW, IP_HASH);

		// Assert
		assertThat(publishedRequest().messageAttributes())
				.containsOnlyKeys(MessageAttributeKeys.TRACE_PARENT, MessageAttributeKeys.CORRELATION_ID);
	}

	@Test
	void publish_shouldPassTheTraceStateOn_whenTheRequestArrivedWithOne() {
		// Arrange
		givenPublishSucceeds();
		final Span requestSpan = startRequestSpan(Map.of(
				"traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
				"tracestate", "vendor=value"));

		// Act
		try (Tracer.SpanInScope ignored = tracing.tracer().withSpan(requestSpan)) {
			clickEventPublisher.publish(SHORT_CODE, ClickOutcome.RESOLVED, REFERER_DOMAIN, USER_AGENT_RAW, IP_HASH);
		}

		// Assert
		assertThat(publishedRequest().messageAttributes().get(MessageAttributeKeys.TRACE_STATE).stringValue())
				.isEqualTo("vendor=value");
	}

	@Test
	void publish_shouldOnlySendTheKeysTheSharedShapeDefines_whenThePropagatorWritesOthers() {
		// Arrange
		tracing = new OtelTracingFixture(new ExtraFieldPropagator());
		clickEventPublisher = new ClickEventPublisher(snsAsyncClient, awsProperties(), new ObjectMapper(),
				tracing.tracer(), tracing.propagator());
		givenPublishSucceeds();

		// Act
		clickEventPublisher.publish(SHORT_CODE, ClickOutcome.RESOLVED, REFERER_DOMAIN, USER_AGENT_RAW, IP_HASH);

		// Assert
		assertThat(publishedRequest().messageAttributes()).doesNotContainKey("x-extra");
	}

	@Test
	void publish_shouldStartAProducerSpanForTheTopic_whenPublishing() {
		// Arrange
		givenPublishSucceeds();

		// Act
		clickEventPublisher.publish(SHORT_CODE, ClickOutcome.RESOLVED, REFERER_DOMAIN, USER_AGENT_RAW, IP_HASH);

		// Assert
		final SpanData span = onlySpan();
		assertThat(span.getName()).isEqualTo("click-events publish");
		assertThat(span.getKind()).isEqualTo(SpanKind.PRODUCER);
		assertThat(span.getAttributes().get(AttributeKey.stringKey("messaging.system"))).isEqualTo("aws_sns");
		assertThat(span.getAttributes().get(AttributeKey.stringKey("messaging.destination.name")))
				.isEqualTo("click-events");
		assertThat(span.getAttributes().get(AttributeKey.stringKey("messaging.operation"))).isEqualTo("publish");
	}

	@Test
	void publish_shouldMakeThePublishSpanAChildOfTheRequestSpan_whenARequestSpanIsActive() {
		// Arrange
		givenPublishSucceeds();
		final Span requestSpan = startRequestSpan(Map.of());

		// Act
		try (Tracer.SpanInScope ignored = tracing.tracer().withSpan(requestSpan)) {
			clickEventPublisher.publish(SHORT_CODE, ClickOutcome.RESOLVED, REFERER_DOMAIN, USER_AGENT_RAW, IP_HASH);
		}

		// Assert
		final SpanData publishSpan = onlySpan();
		assertThat(publishSpan.getParentSpanId()).isEqualTo(requestSpan.context().spanId());
		assertThat(publishSpan.getTraceId()).isEqualTo(requestSpan.context().traceId());
	}

	@Test
	void publish_shouldHaveThePublishSpanCurrent_whileCallingSns() {
		// Arrange
		final AtomicReference<Span> currentDuringPublish = new AtomicReference<>();
		given(snsAsyncClient.publish(any(PublishRequest.class))).willAnswer(invocation -> {
			currentDuringPublish.set(tracing.tracer().currentSpan());
			return CompletableFuture.completedFuture(PublishResponse.builder().build());
		});

		// Act
		clickEventPublisher.publish(SHORT_CODE, ClickOutcome.RESOLVED, REFERER_DOMAIN, USER_AGENT_RAW, IP_HASH);

		// Assert
		// So any instrumentation underneath the SDK call sees the publish span as its parent.
		assertThat(currentDuringPublish.get()).isNotNull();
		assertThat(currentDuringPublish.get().context().spanId()).isEqualTo(onlySpan().getSpanId());
	}

	@Test
	void publish_shouldEndThePublishSpanWithoutAnError_whenSnsPublishSucceeds() {
		// Arrange
		givenPublishSucceeds();

		// Act
		clickEventPublisher.publish(SHORT_CODE, ClickOutcome.RESOLVED, REFERER_DOMAIN, USER_AGENT_RAW, IP_HASH);

		// Assert
		// Only an ended span reaches the collector, so finding it at all proves it ended.
		assertThat(onlySpan().getStatus().getStatusCode()).isNotEqualTo(StatusCode.ERROR);
	}

	@Test
	void publish_shouldEndThePublishSpanAsAnError_whenSnsPublishFails() {
		// Arrange
		given(snsAsyncClient.publish(any(PublishRequest.class)))
				.willReturn(CompletableFuture.failedFuture(new RuntimeException("SNS unavailable")));

		// Act
		clickEventPublisher.publish(SHORT_CODE, ClickOutcome.RESOLVED, REFERER_DOMAIN, USER_AGENT_RAW, IP_HASH);

		// Assert
		final SpanData span = onlySpan();
		assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
		assertThat(span.getEvents()).anyMatch(event -> event.getName().equals("exception"));
	}

	@Test
	void publish_shouldEndThePublishSpanAndRethrow_whenSnsClientThrowsSynchronously() {
		// Arrange
		given(snsAsyncClient.publish(any(PublishRequest.class))).willThrow(new IllegalStateException("client closed"));

		// Act & Assert
		// Nothing else would end the span: the completion callback was never registered.
		assertThatThrownBy(() -> clickEventPublisher.publish(
				SHORT_CODE, ClickOutcome.RESOLVED, REFERER_DOMAIN, USER_AGENT_RAW, IP_HASH))
				.isInstanceOf(IllegalStateException.class);
		assertThat(onlySpan().getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
	}

	@Test
	void publish_shouldNotStartASpan_whenEventFailsToSerialize() throws JsonProcessingException {
		// Arrange
		final ObjectMapper failingObjectMapper = mock(ObjectMapper.class);
		willThrow(new JsonMappingException(null, "boom")).given(failingObjectMapper).writeValueAsString(any());
		clickEventPublisher = new ClickEventPublisher(snsAsyncClient, awsProperties(), failingObjectMapper,
				tracing.tracer(), tracing.propagator());

		// Act
		clickEventPublisher.publish(SHORT_CODE, ClickOutcome.RESOLVED, REFERER_DOMAIN, USER_AGENT_RAW, IP_HASH);

		// Assert
		assertThat(tracing.finishedSpans()).isEmpty();
	}

	private AwsProperties awsProperties() {
		final AwsProperties.Sns sns = new AwsProperties.Sns();
		sns.setTopics(Map.of(AwsConstants.TOPIC_CLICK_EVENTS, TOPIC_ARN));
		final AwsProperties awsProperties = new AwsProperties();
		awsProperties.setSns(sns);
		return awsProperties;
	}

	private void givenPublishSucceeds() {
		given(snsAsyncClient.publish(any(PublishRequest.class)))
				.willReturn(CompletableFuture.completedFuture(PublishResponse.builder().build()));
	}

	private PublishRequest publishedRequest() {
		final ArgumentCaptor<PublishRequest> requestCaptor = ArgumentCaptor.forClass(PublishRequest.class);
		verify(snsAsyncClient).publish(requestCaptor.capture());
		return requestCaptor.getValue();
	}

	/**
	 * Starts a span standing in for the redirect request, continuing the given incoming headers as a real request would.
	 */
	private Span startRequestSpan(final Map<String, String> incomingHeaders) {
		return tracing.propagator().extract(incomingHeaders, Map::get)
				.name("http get /{shortCode}")
				.kind(Span.Kind.SERVER)
				.start();
	}

	/**
	 * The one span that has ended, which for most tests is the publish span.
	 */
	private SpanData onlySpan() {
		assertThat(tracing.finishedSpans()).hasSize(1);
		return tracing.finishedSpans().getFirst();
	}

	/**
	 * Writes a field the shared message-attribute shape does not define, as a baggage propagator would.
	 */
	private static final class ExtraFieldPropagator implements TextMapPropagator {

		@Override
		public Collection<String> fields() {
			return List.of("x-extra");
		}

		@Override
		public <C> void inject(final io.opentelemetry.context.Context context, final C carrier,
							   final TextMapSetter<C> setter) {
			setter.set(carrier, "x-extra", "value");
		}

		@Override
		public <C> io.opentelemetry.context.Context extract(final io.opentelemetry.context.Context context,
															 final C carrier, final TextMapGetter<C> getter) {
			return context;
		}

	}

}
