package io.urlshortener.redirectservice.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.urlshortener.eventcontracts.ClickEvent;
import io.urlshortener.eventcontracts.ClickOutcome;
import io.urlshortener.redirectservice.constant.AwsConstants;
import io.urlshortener.redirectservice.property.AwsProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.services.sns.SnsAsyncClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Publishes a {@link ClickEvent} for every redirect attempt to the {@code click-events} SNS topic,
 * fire-and-forget, so that publishing never blocks or fails the redirect itself.
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
		final PublishRequest publishRequest = PublishRequest.builder()
				.topicArn(topicArn)
				.message(message)
				.build();
		snsAsyncClient.publish(publishRequest)
				.whenComplete((response, t) -> onPublishComplete(t, shortCode, outcome));
	}

	/**
	 * Logs the outcome of an asynchronous SNS publish, without propagating any failure.
	 *
	 * @param t         the failure raised by the publish call, or {@code null} on success.
	 * @param shortCode the short code the published event was for.
	 * @param outcome   the outcome the published event recorded.
	 */
	private void onPublishComplete(final Throwable t, final String shortCode,
								   final ClickOutcome outcome) {
		if (Objects.nonNull(t)) {
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
