package io.urlshortener.redirectservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.urlshortener.redirectservice.model.cache.CachedLink;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Provides the {@link RedisTemplate} bean used to cache {@link CachedLink} entries by short code.
 */
@Slf4j
@Configuration
public class RedisConfig {

	/**
	 * Builds the Redis template used by {@link io.urlshortener.redirectservice.service.LinkLookupService
	 * LinkLookupService} to cache lookups by short code.
	 *
	 * @param connectionFactory the Redis connection factory, auto-configured from {@code spring.data.redis.*}.
	 * @param objectMapper      Spring's own auto-configured {@link ObjectMapper}, reused here so JSR-310 types
	 *                          like {@link java.time.Instant} (used by {@link
	 *                          io.urlshortener.redirectservice.model.domainObject.ShortLink ShortLink}) serialize
	 *                          correctly - a bare {@code new ObjectMapper()} has no such support registered.
	 * @return a {@link RedisTemplate} keyed by plain string, valued by JSON-serialized {@link CachedLink}.
	 */
	@Bean
	public RedisTemplate<String, CachedLink> linkRedisTemplate(final RedisConnectionFactory connectionFactory,
															   final ObjectMapper objectMapper) {
		final RedisTemplate<String, CachedLink> template = new RedisTemplate<>();
		template.setConnectionFactory(connectionFactory);
		template.setKeySerializer(new StringRedisSerializer());
		template.setValueSerializer(new Jackson2JsonRedisSerializer<>(objectMapper, CachedLink.class));
		log.atDebug()
				.addKeyValue("bean", RedisTemplate.class.getSimpleName())
				.log("Bean: RedisTemplate created");
		return template;
	}

}
