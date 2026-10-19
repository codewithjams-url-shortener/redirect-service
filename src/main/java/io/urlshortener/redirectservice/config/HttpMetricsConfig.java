package io.urlshortener.redirectservice.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.urlshortener.redirectservice.metrics.HttpMetricsCloudWatchPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;

import java.time.Clock;

/**
 * Wires the publisher of the HTTP response counts behind the AppConfig rollback alarm and turns on scheduling so it
 * runs. Set {@code url-shortener.http-metrics.enabled} to {@code false} to switch it off, e.g. where there is no
 * CloudWatch to publish to; it is on by default, since an unpublished metric leaves the alarm blind.
 */
@Slf4j
@Configuration
@EnableScheduling
@ConditionalOnProperty(
		prefix = "url-shortener.http-metrics",
		name = "enabled",
		havingValue = "true",
		matchIfMissing = true
)
public class HttpMetricsConfig {

	/**
	 * Builds the publisher that sends the response counts to CloudWatch on a schedule.
	 *
	 * @param meterRegistry    the registry holding the {@code http.server.requests} timers.
	 * @param cloudWatchClient the client the counts are sent with.
	 * @param serviceName      this service's name, which the counts are published under.
	 * @return the scheduled {@link HttpMetricsCloudWatchPublisher}.
	 */
	@Bean
	public HttpMetricsCloudWatchPublisher httpMetricsCloudWatchPublisher(
			final MeterRegistry meterRegistry,
			final CloudWatchClient cloudWatchClient,
			@Value("${spring.application.name}") final String serviceName
	) {
		log.atDebug()
				.addKeyValue("bean", HttpMetricsCloudWatchPublisher.class.getSimpleName())
				.addKeyValue("service", serviceName)
				.log("Bean: HttpMetricsCloudWatchPublisher created");
		return new HttpMetricsCloudWatchPublisher(meterRegistry, cloudWatchClient, Clock.systemUTC(), serviceName);
	}

}
