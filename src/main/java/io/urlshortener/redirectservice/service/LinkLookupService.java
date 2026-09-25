package io.urlshortener.redirectservice.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.urlshortener.redirectservice.model.cache.CachedLink;
import io.urlshortener.redirectservice.model.domainObject.ShortLink;
import io.urlshortener.redirectservice.property.UrlRedirectionProperties;
import io.urlshortener.redirectservice.repository.LinkRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Cache-aside lookup of a link by its short code: Redis first, falling back to DynamoDB on a cache
 * miss and populating the cache afterward.
 *
 * <p>
 *     A DynamoDB miss (short code truly doesn't exist) is cached too, as a {@link CachedLink} with
 *     {@code found = false} - negative caching, protecting DynamoDB from repeated lookups of the same nonexistent code.
 *     A cached entry, hit or negative, is trusted as-is with no further DynamoDB call.
 * </p>
 *
 * <p>
 *     The TTL applied to every cache entry here is a safety net bounding staleness if something goes wrong, not the
 *     primary correctness mechanism. Actual cache invalidation on a link edit/delete is driven reactively by a DynamoDB
 *     Streams consumer (tracked separately).
 * </p>
 */
@Slf4j
@Service
public class LinkLookupService {

	/**
	 * Looks up the persisted link.
	 */
	private final LinkRepository linkRepository;

	/**
	 * Redis-backed cache of lookups by short code.
	 */
	private final RedisTemplate<String, CachedLink> redisTemplate;

	/**
	 * Source of the configured cache TTL.
	 */
	private final UrlRedirectionProperties redirectionProperties;

	/**
	 * Counts Redis lookups that found an existing cache entry (positive or negative) - a cache hit.
	 */
	private final Counter cacheHitCounter;

	/**
	 * Counts Redis lookups that found no cache entry, requiring a DynamoDB fallback - a cache miss.
	 */
	private final Counter cacheMissCounter;

	/**
	 * Creates the service and registers its cache hit/miss counters.
	 *
	 * @param linkRepository        the repository to fall back to on a cache miss.
	 * @param redisTemplate         the Redis-backed cache of lookups by short code.
	 * @param redirectionProperties source of the configured cache TTL.
	 * @param meterRegistry         the registry to register {@link #cacheHitCounter} and {@link #cacheMissCounter}
	 *                              against.
	 */
	public LinkLookupService(final LinkRepository linkRepository,
							 final RedisTemplate<String, CachedLink> redisTemplate,
							 final UrlRedirectionProperties redirectionProperties,
							 final MeterRegistry meterRegistry) {
		this.linkRepository = linkRepository;
		this.redisTemplate = redisTemplate;
		this.redirectionProperties = redirectionProperties;
		this.cacheHitCounter = Counter.builder("cache.lookup")
				.description("Number of Redis lookups for a short code, tagged by hit/miss")
				.tag("result", "hit")
				.register(meterRegistry);
		this.cacheMissCounter = Counter.builder("cache.lookup")
				.description("Number of Redis lookups for a short code, tagged by hit/miss")
				.tag("result", "miss")
				.register(meterRegistry);
	}

	/**
	 * Looks up a link by its short code, cache-first.
	 *
	 * @param shortCode the short code to look up.
	 * @return the matching link, or {@link Optional#empty()} if no link exists for that short code
	 * (whether confirmed just now via DynamoDB, or already known from a prior negative-cache entry).
	 */
	public Optional<ShortLink> getLink(final String shortCode) {
		final String key = "link:%s".formatted(shortCode);
		final CachedLink cached = redisTemplate.opsForValue().get(key);

		if (Objects.nonNull(cached)) {

			cacheHitCounter.increment();
			log.atDebug()
					.addKeyValue("shortCode", shortCode)
					.addKeyValue("key", key)
					.log("Cache Hit");

			if (cached.found()) {
				log.atInfo()
						.addKeyValue("shortCode", shortCode)
						.addKeyValue("presentInCache", true)
						.addKeyValue("presentInDB", true)
						.log("Short Code present in Cache");
				return Optional.of(cached.link());
			} else {
				log.atInfo()
						.addKeyValue("shortCode", shortCode)
						.addKeyValue("presentInCache", true)
						.addKeyValue("presentInDB", false)
						.log("Short Code absent in DB after retrieving from Cache");
				return Optional.empty();
			}

		} else {
			cacheMissCounter.increment();
			log.atDebug()
					.addKeyValue("shortCode", shortCode)
					.addKeyValue("key", key)
					.log("Cache Miss");
		}

		final Optional<ShortLink> fromDb = linkRepository.findByShortCode(shortCode);
		final Duration ttl = redirectionProperties.getCache().getTtl();

		if (fromDb.isPresent()) {
			log.atInfo()
					.addKeyValue("shortCode", shortCode)
					.addKeyValue("presentInCache", false)
					.addKeyValue("presentInDB", true)
					.log("Short Code found in DB after Cache miss");
			redisTemplate.opsForValue().set(key, new CachedLink(true, fromDb.get()), ttl);
		} else {
			log.atInfo()
					.addKeyValue("shortCode", shortCode)
					.addKeyValue("presentInCache", false)
					.addKeyValue("presentInDB", false)
					.log("Short Code not found in DB after Cache miss");
			redisTemplate.opsForValue().set(key, new CachedLink(false, null), ttl);
		}

		log.atDebug()
				.addKeyValue("shortCode", shortCode)
				.addKeyValue("key", key)
				.log("Cache Key updated after DB fetch");

		return fromDb;
	}

}
