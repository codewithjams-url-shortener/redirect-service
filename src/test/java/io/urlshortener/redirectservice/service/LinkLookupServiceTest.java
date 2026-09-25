package io.urlshortener.redirectservice.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.urlshortener.redirectservice.model.cache.CachedLink;
import io.urlshortener.redirectservice.model.domainObject.ShortLink;
import io.urlshortener.redirectservice.property.UrlRedirectionProperties;
import io.urlshortener.redirectservice.repository.LinkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class LinkLookupServiceTest {

	private static final String SHORT_CODE = "abc1234";

	private static final String CACHE_KEY = "link:abc1234";

	private static final Duration TTL = Duration.ofMinutes(10);

	@Mock
	private LinkRepository linkRepository;

	@Mock
	private RedisTemplate<String, CachedLink> redisTemplate;

	@Mock
	private ValueOperations<String, CachedLink> valueOperations;

	private SimpleMeterRegistry meterRegistry;

	private LinkLookupService linkLookupService;

	@BeforeEach
	void setUp() {
		final UrlRedirectionProperties redirectionProperties = new UrlRedirectionProperties();
		final UrlRedirectionProperties.Cache cache = new UrlRedirectionProperties.Cache();
		cache.setTtl(TTL);
		redirectionProperties.setCache(cache);

		meterRegistry = new SimpleMeterRegistry();
		linkLookupService = new LinkLookupService(linkRepository, redisTemplate, redirectionProperties, meterRegistry);
		given(redisTemplate.opsForValue()).willReturn(valueOperations);
	}

	private double cacheHitCount() {
		return meterRegistry.counter("cache.lookup", "result", "hit").count();
	}

	private double cacheMissCount() {
		return meterRegistry.counter("cache.lookup", "result", "miss").count();
	}

	@Test
	void getLink_shouldReturnCachedLinkAndIncrementHitCounter_whenCacheHasAPositiveEntry() {
		// Arrange
		final ShortLink cachedLink = ShortLink.builder().shortCode(SHORT_CODE).longUrl("https://example.com").build();
		given(valueOperations.get(CACHE_KEY)).willReturn(new CachedLink(true, cachedLink));

		// Act
		final Optional<ShortLink> result = linkLookupService.getLink(SHORT_CODE);

		// Assert
		assertThat(result).contains(cachedLink);
		verify(linkRepository, never()).findByShortCode(anyString());
		assertThat(cacheHitCount()).isEqualTo(1.0);
		assertThat(cacheMissCount()).isZero();
	}

	@Test
	void getLink_shouldReturnEmptyAndIncrementHitCounter_whenCacheHasANegativeEntry() {
		// Arrange
		given(valueOperations.get(CACHE_KEY)).willReturn(new CachedLink(false, null));

		// Act
		final Optional<ShortLink> result = linkLookupService.getLink(SHORT_CODE);

		// Assert
		assertThat(result).isEmpty();
		verify(linkRepository, never()).findByShortCode(anyString());
		assertThat(cacheHitCount()).isEqualTo(1.0);
		assertThat(cacheMissCount()).isZero();
	}

	@Test
	void getLink_shouldReturnDbResultCacheItAndIncrementMissCounter_whenCacheMissesAndDbHasTheLink() {
		// Arrange
		final ShortLink dbLink = ShortLink.builder().shortCode(SHORT_CODE).longUrl("https://example.com").build();
		given(valueOperations.get(CACHE_KEY)).willReturn(null);
		given(linkRepository.findByShortCode(SHORT_CODE)).willReturn(Optional.of(dbLink));

		// Act
		final Optional<ShortLink> result = linkLookupService.getLink(SHORT_CODE);

		// Assert
		assertThat(result).contains(dbLink);
		verify(valueOperations).set(CACHE_KEY, new CachedLink(true, dbLink), TTL);
		assertThat(cacheMissCount()).isEqualTo(1.0);
		assertThat(cacheHitCount()).isZero();
	}

	@Test
	void getLink_shouldReturnEmptyCacheANegativeEntryAndIncrementMissCounter_whenCacheMissesAndDbHasNoLink() {
		// Arrange
		given(valueOperations.get(CACHE_KEY)).willReturn(null);
		given(linkRepository.findByShortCode(SHORT_CODE)).willReturn(Optional.empty());

		// Act
		final Optional<ShortLink> result = linkLookupService.getLink(SHORT_CODE);

		// Assert
		assertThat(result).isEmpty();
		verify(valueOperations).set(CACHE_KEY, new CachedLink(false, null), TTL);
		assertThat(cacheMissCount()).isEqualTo(1.0);
		assertThat(cacheHitCount()).isZero();
	}

}
