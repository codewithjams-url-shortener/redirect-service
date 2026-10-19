package io.urlshortener.redirectservice.integrationtest;

import io.urlshortener.servicecommon.HttpMetricContract;
import io.urlshortener.redirectservice.integrationtest.config.CorruptLinkSeeder;
import io.urlshortener.redirectservice.integrationtest.config.IntegrationTestBootstrap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.Datapoint;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricStatisticsRequest;
import software.amazon.awssdk.services.cloudwatch.model.Statistic;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Checks, against a real redirect-service process and floci's CloudWatch, that the service publishes the response counts the
 * AppConfig rollback alarm reads (ADR-0011): requests are sent over HTTP, one of them a genuine 500, and the counts are
 * read back from CloudWatch under the shared metric contract.
 *
 * <p>
 *     Runs only under the {@code local} profile, as it needs the floci that profile launches. The launched service
 *     publishes every second rather than every minute so the test does not have to wait a minute.
 * </p>
 *
 * <p>
 *     The counts only ever grow and other tests share the running service, so the test compares what CloudWatch holds
 *     before and after its own requests and asserts at least what it sent.
 * </p>
 */
@EnabledIfSystemProperty(named = "spring.profiles.active", matches = "local")
@SpringBootTest(
		classes = IntegrationTestBootstrap.class,
		webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class HttpMetricsIntegrationTest {

	/**
	 * The service's name, which it publishes its counts under: its {@code spring.application.name}.
	 */
	private static final String SERVICE_NAME = "redirect-service";

	@Autowired
	private RestClient redirectApiClient;

	@Autowired
	private CloudWatchClient cloudWatchClient;

	@Autowired
	private CorruptLinkSeeder corruptLinkSeeder;

	@Test
	void service_shouldPublishRequestAndServerErrorCountsToCloudWatch_whenItServedRequestsIncludingA500() {

		// Arrange
		final String corruptShortCode = corruptLinkSeeder.seed();
		final double errorsBefore = sum(HttpMetricContract.HTTP_5XX_COUNT);
		final double requestsBefore = sum(HttpMetricContract.HTTP_REQUEST_COUNT);

		// Act
		final HttpStatusCode firstMissing = get("/missing-" + System.nanoTime());
		final HttpStatusCode secondMissing = get("/missing-" + System.nanoTime());
		final HttpStatusCode corrupt = get("/" + corruptShortCode);

		// Assert
		assertThat(firstMissing)
				.isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(secondMissing)
				.isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(corrupt)
				.as("the corrupt link provokes a genuine server error")
				.isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		await().atMost(Duration.ofSeconds(30))
				.pollInterval(Duration.ofMillis(500))
				.untilAsserted(
						() -> {
							final double errors = sum(HttpMetricContract.HTTP_5XX_COUNT) - errorsBefore;
							final double requests = sum(HttpMetricContract.HTTP_REQUEST_COUNT) - requestsBefore;
							assertThat(errors)
									.as("5xx responses published")
									.isGreaterThanOrEqualTo(1.0);
							assertThat(requests)
									.as("responses published, 4xx and 5xx included")
									.isGreaterThanOrEqualTo(3.0);
							assertThat(errors)
									.as("5xx responses never exceed all responses")
									.isLessThanOrEqualTo(requests);
						}
				);

	}

	private HttpStatusCode get(final String path) {
		return redirectApiClient.get()
				.uri(path)
				.exchange((request, response) -> response.getStatusCode());
	}

	/**
	 * Everything CloudWatch holds for the metric under this service's dimension, over a window wide enough to cover the
	 * whole test run.
	 */
	private double sum(final String metricName) {
		final Dimension dimension = Dimension.builder()
				.name(HttpMetricContract.SERVICE_DIMENSION)
				.value(SERVICE_NAME)
				.build();
		final GetMetricStatisticsRequest request = GetMetricStatisticsRequest.builder()
				.namespace(HttpMetricContract.NAMESPACE)
				.metricName(metricName)
				.dimensions(dimension)
				.startTime(Instant.now().minus(Duration.ofHours(1)))
				.endTime(Instant.now().plus(Duration.ofMinutes(1)))
				.statistics(Statistic.SUM)
				.period(60)
				.build();
		return cloudWatchClient.getMetricStatistics(request)
				.datapoints()
				.stream()
				.mapToDouble(Datapoint::sum)
				.sum();
	}

}
