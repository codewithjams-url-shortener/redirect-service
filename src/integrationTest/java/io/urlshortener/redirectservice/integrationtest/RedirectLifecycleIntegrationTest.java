package io.urlshortener.redirectservice.integrationtest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.urlshortener.redirectservice.integrationtest.config.DeployedEnvironmentConfig;
import io.urlshortener.redirectservice.integrationtest.config.IntegrationTestBootstrap;
import io.urlshortener.redirectservice.integrationtest.config.LocalEnvironmentConfig;
import io.urlshortener.redirectservice.integrationtest.constant.ClickEventConstants;
import io.urlshortener.redirectservice.integrationtest.constant.LinksApiConstants;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Integration tests for the {@code GET /{shortCode}} redirect contract, shared between two flavors
 * selected via {@code spring.profiles.active}:
 *
 * <ul>
 *     <li>
 *         {@code local} ({@link LocalEnvironmentConfig}) - launches the built {@code redirect-service} and
 *         {@code url-service} jars as separate processes against a Testcontainers-managed floci (DynamoDB, SNS, SQS)
 *         and Redis instance, and hits both over real HTTP.
 *     </li>
 *     <li>
 *         {@code deployed} ({@link DeployedEnvironmentConfig}) - hits already-deployed {@code redirect-service} and
 *         {@code url-service} instances over real HTTP, and the real {@code click-events} SNS topic.
 *     </li>
 * </ul>
 *
 * <p>
 *     {@code url-service} is used only to create/remove the Links rows {@code redirect-service} has no write endpoint
 *     of its own for - this suite otherwise never touches url-service's redirect behavior.
 * </p>
 *
 * <p>
 *     Requests and responses are handled as raw JSON ({@link JsonNode}) rather than either service's own generated
 *     DTOs, so this suite has no compile-time dependency on the applications it is testing. It verifies the wire
 *     contract, not compile-time type compatibility with either app's internals.
 * </p>
 */
@SpringBootTest(
		classes = IntegrationTestBootstrap.class,
		webEnvironment = SpringBootTest.WebEnvironment.NONE
)
public class RedirectLifecycleIntegrationTest {

	private static final String LINK_WITH_LONG_URL_TEMPLATE = loadRequestTemplate("link-with-long-url.json");

	private static final String PATCH_EXPIRES_AT_TEMPLATE = loadRequestTemplate("patch-expires-at.json");

	@Autowired
	private RestClient redirectApiClient;

	@Autowired
	private RestClient linksApiClient;

	@Autowired
	private SqsClient clickEventsSqsClient;

	@Autowired
	private String clickEventsQueueUrl;

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void redirect_shouldReturn302ToLongUrlAndPublishResolved_whenLinkExists() {

		// Arrange
		final String longUrl = "https://example.com/some-page";
		final JsonNode created = createLink(longUrl);
		final String shortCode = created.get(LinksApiConstants.FIELD_SHORT_CODE).asText();

		// Act
		final ResponseEntity<Void> response = redirect(shortCode);

		// Assert
		assertThat(response.getStatusCode())
				.as("GET /%s for an existing link should return 302 Found", shortCode)
				.isEqualTo(HttpStatus.FOUND);
		assertThat(response.getHeaders().getLocation())
				.as("Location header should point at the link's longUrl")
				.hasToString(longUrl);
		assertClickEventPublished(shortCode, ClickEventConstants.OUTCOME_RESOLVED);

	}

	@Test
	void redirect_shouldReturn404AndPublishNotFound_whenShortCodeWasNeverCreated() {

		// Arrange
		final String shortCode = "missing" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

		// Act
		final Throwable thrown = catchThrowable(() -> redirect(shortCode));

		// Assert
		assertHttpStatus(thrown, HttpStatus.NOT_FOUND,
				"GET /%s for a short code that was never created should return 404 Not Found".formatted(shortCode));
		assertClickEventPublished(shortCode, ClickEventConstants.OUTCOME_NOT_FOUND);

	}

	@Test
	void redirect_shouldReturn404AndPublishExpired_whenLinkHasExpired() throws InterruptedException {

		// Arrange
		final JsonNode created = createLink("https://example.com/will-expire");
		final String shortCode = created.get(LinksApiConstants.FIELD_SHORT_CODE).asText();
		final String managementToken = created.get(LinksApiConstants.FIELD_MANAGEMENT_TOKEN).asText();
		// Must be in the future at request time (PATCH's expiresAt is @Future-validated), so the link is
		// set to expire shortly after creation rather than created already-expired.
		final Instant expiresAt = Instant.now().plus(2, ChronoUnit.SECONDS);
		patchExpiresAt(shortCode, managementToken, expiresAt);
		Thread.sleep(3_000);

		// Act
		final Throwable thrown = catchThrowable(() -> redirect(shortCode));

		// Assert
		assertHttpStatus(thrown, HttpStatus.NOT_FOUND,
				"GET /%s for an expired link should return 404 Not Found".formatted(shortCode));
		assertClickEventPublished(shortCode, ClickEventConstants.OUTCOME_EXPIRED);

	}

	@Test
	void redirect_shouldServeFromCache_whenUnderlyingLinkWasDeletedAfterFirstRedirect() {

		// Arrange
		final String longUrl = "https://example.com/cached-page";
		final JsonNode created = createLink(longUrl);
		final String shortCode = created.get(LinksApiConstants.FIELD_SHORT_CODE).asText();
		final String managementToken = created.get(LinksApiConstants.FIELD_MANAGEMENT_TOKEN).asText();

		// Act
		// First redirect: cache miss, served from and cached off DynamoDB.
		final ResponseEntity<Void> firstResponse = redirect(shortCode);
		deleteLink(shortCode, managementToken);
		// Second redirect: the link no longer exists in DynamoDB at all, so a 302 here can only be
		// explained by the first request's cache entry still being served.
		final ResponseEntity<Void> secondResponse = redirect(shortCode);

		// Assert
		assertThat(firstResponse.getStatusCode())
				.as("First GET /%s (cache miss) should return 302 Found", shortCode)
				.isEqualTo(HttpStatus.FOUND);
		assertThat(secondResponse.getStatusCode())
				.as("Second GET /%s, after the underlying link was deleted, should still return 302 Found "
						+ "(served from cache)", shortCode)
				.isEqualTo(HttpStatus.FOUND);
		assertThat(secondResponse.getHeaders().getLocation())
				.as("Cached redirect should still point at the original longUrl")
				.hasToString(longUrl);

	}

	private JsonNode createLink(final String longUrl) {
		return linksApiClient.post()
				.uri("/links")
				.contentType(MediaType.APPLICATION_JSON)
				.body(LINK_WITH_LONG_URL_TEMPLATE.formatted(longUrl))
				.retrieve()
				.body(JsonNode.class);
	}

	private void patchExpiresAt(final String shortCode, final String managementToken, final Instant expiresAt) {
		linksApiClient.patch()
				.uri("/links/{shortCode}", shortCode)
				.header(LinksApiConstants.HEADER_MANAGEMENT_TOKEN, managementToken)
				.contentType(MediaType.APPLICATION_JSON)
				.body(PATCH_EXPIRES_AT_TEMPLATE.formatted(expiresAt))
				.retrieve()
				.toBodilessEntity();
	}

	private void deleteLink(final String shortCode, final String managementToken) {
		linksApiClient.delete()
				.uri("/links/{shortCode}", shortCode)
				.header(LinksApiConstants.HEADER_MANAGEMENT_TOKEN, managementToken)
				.retrieve()
				.toBodilessEntity();
	}

	private ResponseEntity<Void> redirect(final String shortCode) {
		return redirectApiClient.get()
				.uri("/{shortCode}", shortCode)
				.retrieve()
				.toBodilessEntity();
	}

	private void assertClickEventPublished(final String shortCode, final String expectedOutcome) {
		final JsonNode event = pollForClickEvent(shortCode);
		assertThat(event)
				.as("A ClickEvent for shortCode '%s' should have been published to the click-events topic", shortCode)
				.isNotNull();
		assertThat(event.get(ClickEventConstants.FIELD_OUTCOME).asText())
				.as("ClickEvent published for shortCode '%s' should carry the correct outcome", shortCode)
				.isEqualTo(expectedOutcome);
	}

	private JsonNode pollForClickEvent(final String shortCode) {
		final Instant deadline = Instant.now().plusSeconds(10);
		while (Instant.now().isBefore(deadline)) {
			final ReceiveMessageRequest receiveMessageRequest = ReceiveMessageRequest.builder()
					.queueUrl(clickEventsQueueUrl)
					.maxNumberOfMessages(10)
					.waitTimeSeconds(2)
					.build();
			final List<Message> messages = clickEventsSqsClient.receiveMessage(receiveMessageRequest).messages();
			for (final Message message : messages) {
				final JsonNode event = parseClickEvent(message.body());
				if (event.get(ClickEventConstants.FIELD_SHORT_CODE).asText().equals(shortCode)) {
					final DeleteMessageRequest deleteMessageRequest = DeleteMessageRequest.builder()
							.queueUrl(clickEventsQueueUrl)
							.receiptHandle(message.receiptHandle())
							.build();
					clickEventsSqsClient.deleteMessage(deleteMessageRequest);
					return event;
				}
			}
		}
		return null;
	}

	private JsonNode parseClickEvent(final String body) {
		try {
			return objectMapper.readTree(body);
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to parse ClickEvent message body: %s".formatted(body), e);
		}
	}

	private static void assertHttpStatus(final Throwable thrown, final HttpStatus expectedStatus,
										  final String description) {
		assertThat(thrown)
				.as("%s (expected an HttpClientErrorException to be thrown)", description)
				.isInstanceOf(HttpClientErrorException.class);
		final HttpStatusCode actualStatus = ((HttpClientErrorException) thrown).getStatusCode();
		assertThat(actualStatus)
				.as(description)
				.isEqualTo(expectedStatus);
	}

	private static String loadRequestTemplate(final String fileName) {
		final ClassPathResource resource = new ClassPathResource("requests/" + fileName);
		try {
			return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to load request template: %s".formatted(fileName), e);
		}
	}

}
