package io.urlshortener.redirectservice.service;

import io.urlshortener.eventcontracts.ClickOutcome;
import io.urlshortener.redirectservice.exception.ShortLinkExpiredException;
import io.urlshortener.redirectservice.exception.ShortLinkNotFoundException;
import io.urlshortener.redirectservice.model.domainObject.ShortLink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RedirectServiceTest {

	private static final String SHORT_CODE = "abc1234";

	private static final String REFERER_DOMAIN = "referrer.com";

	private static final String USER_AGENT_RAW = "Mozilla/5.0";

	private static final String IP_HASH = "hashedIp";

	@Mock
	private LinkLookupService linkLookupService;

	@Mock
	private ClickEventPublisher clickEventPublisher;

	private RedirectService redirectService;

	@BeforeEach
	void setUp() {
		redirectService = new RedirectService(linkLookupService, clickEventPublisher);
	}

	@Test
	void resolve_shouldThrowShortLinkNotFoundExceptionAndPublishNotFound_whenLinkDoesNotExist() {
		// Arrange
		given(linkLookupService.getLink(SHORT_CODE)).willReturn(Optional.empty());

		// Act & Assert
		assertThatThrownBy(() -> redirectService.resolve(SHORT_CODE, REFERER_DOMAIN, USER_AGENT_RAW, IP_HASH))
				.isInstanceOf(ShortLinkNotFoundException.class);
		verify(clickEventPublisher).publish(SHORT_CODE, ClickOutcome.NOT_FOUND, REFERER_DOMAIN, USER_AGENT_RAW,
				IP_HASH);
	}

	@Test
	void resolve_shouldReturnLongUrlAndPublishResolved_whenLinkExistsAndHasNoExpiresAt() {
		// Arrange
		final ShortLink link = ShortLink.builder()
				.shortCode(SHORT_CODE)
				.longUrl("https://example.com")
				.build();
		given(linkLookupService.getLink(SHORT_CODE)).willReturn(Optional.of(link));

		// Act
		final String longUrl = redirectService.resolve(SHORT_CODE, REFERER_DOMAIN, USER_AGENT_RAW, IP_HASH);

		// Assert
		assertThat(longUrl).isEqualTo("https://example.com");
		verify(clickEventPublisher).publish(SHORT_CODE, ClickOutcome.RESOLVED, REFERER_DOMAIN, USER_AGENT_RAW,
				IP_HASH);
	}

	@Test
	void resolve_shouldReturnLongUrlAndPublishResolved_whenLinkExpiresAtIsInTheFuture() {
		// Arrange
		final ShortLink link = ShortLink.builder()
				.shortCode(SHORT_CODE)
				.longUrl("https://example.com")
				.expiresAt(Instant.now().plus(1, ChronoUnit.DAYS))
				.build();
		given(linkLookupService.getLink(SHORT_CODE)).willReturn(Optional.of(link));

		// Act
		final String longUrl = redirectService.resolve(SHORT_CODE, REFERER_DOMAIN, USER_AGENT_RAW, IP_HASH);

		// Assert
		assertThat(longUrl).isEqualTo("https://example.com");
		verify(clickEventPublisher).publish(SHORT_CODE, ClickOutcome.RESOLVED, REFERER_DOMAIN, USER_AGENT_RAW,
				IP_HASH);
	}

	@Test
	void resolve_shouldThrowShortLinkExpiredExceptionAndPublishExpired_whenLinkExpiresAtIsInThePast() {
		// Arrange
		final ShortLink link = ShortLink.builder()
				.shortCode(SHORT_CODE)
				.longUrl("https://example.com")
				.expiresAt(Instant.now().minus(1, ChronoUnit.DAYS))
				.build();
		given(linkLookupService.getLink(SHORT_CODE)).willReturn(Optional.of(link));

		// Act & Assert
		assertThatThrownBy(() -> redirectService.resolve(SHORT_CODE, REFERER_DOMAIN, USER_AGENT_RAW, IP_HASH))
				.isInstanceOf(ShortLinkExpiredException.class);
		verify(clickEventPublisher).publish(SHORT_CODE, ClickOutcome.EXPIRED, REFERER_DOMAIN, USER_AGENT_RAW,
				IP_HASH);
	}

}
