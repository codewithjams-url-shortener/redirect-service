package io.urlshortener.redirectservice.property;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Binds the {@code url-redirection.*} configuration properties used by
 * {@link io.urlshortener.redirectservice.service.LinkLookupService LinkLookupService}.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "url-redirection")
public class UrlRedirectionProperties {

	/**
	 * Cache-specific configuration.
	 */
	private Cache cache;

	/**
	 * Configuration for the Redis cache fronting DynamoDB lookups.
	 */
	@Getter
	@Setter
	public static class Cache {

		/**
		 * How long a cached entry (hit or negative-cache miss) is kept in Redis before falling back to
		 * DynamoDB again. A safety net bounding staleness, not the primary correctness mechanism.
		 */
		private Duration ttl;

	}

}
