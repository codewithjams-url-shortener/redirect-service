package io.urlshortener.redirectservice.service;

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

@ExtendWith(MockitoExtension.class)
class RedirectServiceTest {

	private static final String SHORT_CODE = "abc1234";

	@Mock
	private LinkLookupService linkLookupService;

	private RedirectService redirectService;

	@BeforeEach
	void setUp() {
		redirectService = new RedirectService(linkLookupService);
	}

	@Test
	void resolve_shouldThrowShortLinkNotFoundException_whenLinkDoesNotExist() {
		// Arrange
		given(linkLookupService.getLink(SHORT_CODE)).willReturn(Optional.empty());

		// Act & Assert
		assertThatThrownBy(() -> redirectService.resolve(SHORT_CODE))
				.isInstanceOf(ShortLinkNotFoundException.class);
	}

	@Test
	void resolve_shouldReturnLongUrl_whenLinkExistsAndHasNoExpiresAt() {
		// Arrange
		final ShortLink link = ShortLink.builder()
				.shortCode(SHORT_CODE)
				.longUrl("https://example.com")
				.build();
		given(linkLookupService.getLink(SHORT_CODE)).willReturn(Optional.of(link));

		// Act
		final String longUrl = redirectService.resolve(SHORT_CODE);

		// Assert
		assertThat(longUrl).isEqualTo("https://example.com");
	}

	@Test
	void resolve_shouldReturnLongUrl_whenLinkExpiresAtIsInTheFuture() {
		// Arrange
		final ShortLink link = ShortLink.builder()
				.shortCode(SHORT_CODE)
				.longUrl("https://example.com")
				.expiresAt(Instant.now().plus(1, ChronoUnit.DAYS))
				.build();
		given(linkLookupService.getLink(SHORT_CODE)).willReturn(Optional.of(link));

		// Act
		final String longUrl = redirectService.resolve(SHORT_CODE);

		// Assert
		assertThat(longUrl).isEqualTo("https://example.com");
	}

	@Test
	void resolve_shouldThrowShortLinkExpiredException_whenLinkExpiresAtIsInThePast() {
		// Arrange
		final ShortLink link = ShortLink.builder()
				.shortCode(SHORT_CODE)
				.longUrl("https://example.com")
				.expiresAt(Instant.now().minus(1, ChronoUnit.DAYS))
				.build();
		given(linkLookupService.getLink(SHORT_CODE)).willReturn(Optional.of(link));

		// Act & Assert
		assertThatThrownBy(() -> redirectService.resolve(SHORT_CODE))
				.isInstanceOf(ShortLinkExpiredException.class);
	}

}
