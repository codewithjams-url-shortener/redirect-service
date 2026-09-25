package io.urlshortener.redirectservice.mapper.dbMapper;

import io.urlshortener.linkscontract.Link;
import io.urlshortener.redirectservice.exception.InvalidConversionInputException;
import io.urlshortener.redirectservice.model.domainObject.ShortLink;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LinkDbMapperTest {

	private final LinkDbMapper mapper = new LinkDbMapper();

	@Test
	void toDomain_shouldThrowInvalidConversionInputException_whenEntityIsNull() {
		// Arrange
		// (null is the input under test)

		// Act & Assert
		assertThatThrownBy(() -> mapper.toDomain(null))
				.isInstanceOf(InvalidConversionInputException.class);
	}

	@Test
	void toDomain_shouldMapShortCodeAndLongUrl_whenEntityHasThemPopulated() {
		// Arrange
		final Link entity = new Link("abc1234", "https://example.com", null, 1_000L, null, "hash", null);

		// Act
		final ShortLink domain = mapper.toDomain(entity);

		// Assert
		assertThat(domain.shortCode()).isEqualTo("abc1234");
		assertThat(domain.longUrl()).isEqualTo("https://example.com");
	}

	@Test
	void toDomain_shouldConvertExpiresAtFromEpochSeconds_whenEntityHasAnExpiresAtValue() {
		// Arrange
		final long expiresAtSeconds = Instant.parse("2030-01-01T00:00:00Z").getEpochSecond();
		final Link entity = new Link("abc1234", "https://example.com", null, 1_000L, expiresAtSeconds, "hash", null);

		// Act
		final ShortLink domain = mapper.toDomain(entity);

		// Assert
		assertThat(domain.expiresAt()).isEqualTo(Instant.ofEpochSecond(expiresAtSeconds));
	}

	@Test
	void toDomain_shouldLeaveExpiresAtNull_whenEntityHasNoExpiresAt() {
		// Arrange
		final Link entity = new Link("abc1234", "https://example.com", null, 1_000L, null, "hash", null);

		// Act
		final ShortLink domain = mapper.toDomain(entity);

		// Assert
		assertThat(domain.expiresAt()).isNull();
	}

	@Test
	void toDomain_shouldMapStatus_whenEntityHasAStatus() {
		// Arrange
		final Link entity = new Link("abc1234", "https://example.com", null, 1_000L, null, "hash", "FLAGGED");

		// Act
		final ShortLink domain = mapper.toDomain(entity);

		// Assert
		assertThat(domain.status()).isEqualTo("FLAGGED");
	}

	@Test
	void toDomain_shouldLeaveStatusNull_whenEntityHasNoStatus() {
		// Arrange
		final Link entity = new Link("abc1234", "https://example.com", null, 1_000L, null, "hash", null);

		// Act
		final ShortLink domain = mapper.toDomain(entity);

		// Assert
		assertThat(domain.status()).isNull();
	}

}
