package io.urlshortener.redirectservice.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import io.urlshortener.eventcontracts.ClickEvent;
import io.urlshortener.eventcontracts.ClickOutcome;
import io.urlshortener.redirectservice.constant.AwsConstants;
import io.urlshortener.redirectservice.property.AwsProperties;
import io.urlshortener.servicecommon.MessageAttributeKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.services.sns.SnsAsyncClient;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sns.model.PublishRequest;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Publishes a {@link ClickEvent} for every redirect attempt to the {@code click-events} SNS topic,
 * fire-and-forget, so that publishing never blocks or fails the redirect itself.
 *
 * <p>
 *     Each publish runs inside its own {@code PRODUCER} span, a child of the redirect request's span, and that span's
 *     context travels with the message as SNS message attributes ({@code traceparent}, {@code tracestate} and
 *     {@code correlationId}, under the {@link MessageAttributeKeys} names), because trace context does not cross a queue
 *     hop on its own. A consumer that restores it continues the same trace (ADR-0006).
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClickEventPublisher {

	/**
	 * Publishes messages to SNS asynchronously, without blocking the calling thread.
	 */
	private final SnsAsyncClient snsAsyncClient;

	/**
	 * Resolves the configured SNS topic ARN to publish {@link ClickEvent}s to.
	 */
	private final AwsProperties awsProperties;

	/**
	 * Serializes a {@link ClickEvent} to the JSON message body published to SNS.
	 */
	private final ObjectMapper objectMapper;

	/**
	 * Starts the span around each publish.
	 */
	private final Tracer tracer;

	/**
	 * Writes a span's context into the form (W3C {@code traceparent}/{@code tracestate} by default) that travels in the
	 * message attributes.
	 */
	private final Propagator propagator;

	/**
	 * Builds and publishes a {@code ClickEvent} for a single redirect attempt. Publishing happens
	 * asynchronously and any failure - to serialize or to publish - is logged rather than propagated,
	 * so it can never affect the outcome of the redirect itself.
	 *
	 * @param shortCode     the short code that was requested.
	 * @param outcome       the result of the redirect attempt.
	 * @param refererDomain the domain of the HTTP {@code Referer} header, or {@code null} for direct traffic.
	 * @param userAgentRaw  the raw {@code User-Agent} header of the requesting client.
	 * @param ipHash        a one-way hash of the requesting client's IP address.
	 */
	public void publish(final String shortCode, final ClickOutcome outcome, final String refererDomain,
						final String userAgentRaw, final String ipHash) {
		final String topicArn = awsProperties.getSns().getTopics().get(AwsConstants.TOPIC_CLICK_EVENTS);
		final ClickEvent event = createClickEvent(shortCode, outcome, refererDomain, userAgentRaw, ipHash);
		final String message = parseEventToJson(event);
		if (!StringUtils.hasText(message)) { // Early exit due to event not parsed to JSON.
			return;
		}
		log.atInfo()
				.addKeyValue("shortCode", shortCode)
				.addKeyValue("outcome", outcome.toString())
				.log("Going to publish ClickEvent");
		final Span span = startPublishSpan(topicArn);
		final PublishRequest publishRequest = PublishRequest.builder()
				.topicArn(topicArn)
				.message(message)
				.messageAttributes(traceAttributes(span))
				.build();
		try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
			snsAsyncClient.publish(publishRequest)
					.whenComplete((response, t) -> onPublishComplete(t, shortCode, outcome, span));
		} catch (RuntimeException e) {
			// The publish failed before the callback could be registered, so nothing else will end the span.
			span.error(e);
			span.end();
			throw e;
		}
	}

	/**
	 * Starts the {@code PRODUCER} span that wraps one publish, as a child of the current (request) span if there is one.
	 *
	 * @param topicArn the topic being published to, whose name is recorded on the span.
	 * @return the started span, which the caller must end.
	 */
	private Span startPublishSpan(final String topicArn) {
		final Span.Builder builder = tracer.spanBuilder()
				.name("click-events publish")
				.kind(Span.Kind.PRODUCER)
				.tag("messaging.system", "aws_sns")
				.tag("messaging.destination.name", topicArn.substring(topicArn.lastIndexOf(':') + 1))
				.tag("messaging.operation", "publish");
		final Span current = tracer.currentSpan();
		if (Objects.nonNull(current)) {
			builder.setParent(current.context());
		}
		return builder.start();
	}

	/**
	 * Builds the SNS message attributes that carry the span's context to the consumer.
	 *
	 * <p>
	 *     Only the keys the shared {@link MessageAttributeKeys} defines are sent, whatever else the propagator writes.
	 *     {@code correlationId} is the trace ID: the structured logs already carry it as {@code traceId}, so a log search
	 *     by it works in both services without a second ID scheme.
	 * </p>
	 *
	 * @param span the publish span whose context is sent.
	 * @return the attributes to put on the {@link PublishRequest}; empty entries are left out.
	 */
	private Map<String, MessageAttributeValue> traceAttributes(final Span span) {
		final Map<String, String> carrier = new HashMap<>();
		propagator.inject(span.context(), carrier, Map::put);
		final Map<String, MessageAttributeValue> attributes = new LinkedHashMap<>();
		putIfPresent(attributes, MessageAttributeKeys.TRACE_PARENT, carrier.get(MessageAttributeKeys.TRACE_PARENT));
		putIfPresent(attributes, MessageAttributeKeys.TRACE_STATE, carrier.get(MessageAttributeKeys.TRACE_STATE));
		putIfPresent(attributes, MessageAttributeKeys.CORRELATION_ID, span.context().traceId());
		return attributes;
	}

	/**
	 * Adds a string message attribute unless its value is absent.
	 *
	 * @param attributes the attributes being built.
	 * @param key        the attribute name.
	 * @param value      the attribute value, or {@code null} / blank to leave it out.
	 */
	private void putIfPresent(final Map<String, MessageAttributeValue> attributes, final String key,
							  final String value) {
		if (StringUtils.hasText(value)) {
			attributes.put(key, MessageAttributeValue.builder().dataType("String").stringValue(value).build());
		}
	}

	/**
	 * Logs the outcome of an asynchronous SNS publish, without propagating any failure, and ends the publish span.
	 *
	 * @param t         the failure raised by the publish call, or {@code null} on success.
	 * @param shortCode the short code the published event was for.
	 * @param outcome   the outcome the published event recorded.
	 * @param span      the publish span, ended here whether the publish succeeded or not.
	 */
	private void onPublishComplete(final Throwable t, final String shortCode,
								   final ClickOutcome outcome, final Span span) {
		if (Objects.nonNull(t)) {
			span.error(t);
			log.atWarn()
					.setCause(t)
					.addKeyValue("shortCode", shortCode)
					.addKeyValue("outcome", outcome.toString())
					.log("Failed to publish ClickEvent");
		} else {
			log.atInfo()
					.addKeyValue("shortCode", shortCode)
					.addKeyValue("outcome", outcome.toString())
					.log("ClickEvent published successfully");
		}
		span.end();
	}

	/**
	 * Assembles a {@link ClickEvent} from a redirect attempt's details.
	 *
	 * @param shortCode     the short code that was requested.
	 * @param outcome       the result of the redirect attempt.
	 * @param refererDomain the domain of the HTTP {@code Referer} header, or {@code null} for direct traffic.
	 * @param userAgentRaw  the raw {@code User-Agent} header of the requesting client.
	 * @param ipHash        a one-way hash of the requesting client's IP address.
	 * @return the assembled event, with a freshly generated event ID and the current timestamp.
	 */
	private ClickEvent createClickEvent(final String shortCode, final ClickOutcome outcome, final String refererDomain,
										final String userAgentRaw, final String ipHash) {
		return new ClickEvent(
				UUID.randomUUID().toString(),
				shortCode,
				Instant.now().toEpochMilli(),
				outcome,
				refererDomain,
				userAgentRaw,
				ipHash
		);
	}

	/**
	 * Serializes a {@link ClickEvent} to JSON, for use as the SNS message body.
	 *
	 * @param event the event to serialize.
	 * @return the event's JSON representation, or {@code null} if serialization failed.
	 */
	private String parseEventToJson(final ClickEvent event) {
		try {
			return objectMapper.writeValueAsString(event); // wrap the checked JsonProcessingException
		} catch (JsonProcessingException e) {
			log.atWarn()
					.setCause(e)
					.addKeyValue("shortCode", event.shortCode())
					.addKeyValue("outcome", event.outcome().toString())
					.log("Failed to serialize ClickEvent, skipping publish");
			return null;
		}
	}

}
