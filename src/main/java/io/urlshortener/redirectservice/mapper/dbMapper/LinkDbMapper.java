package io.urlshortener.redirectservice.mapper.dbMapper;

import io.urlshortener.linkscontract.Link;
import io.urlshortener.redirectservice.exception.InvalidConversionInputException;
import io.urlshortener.redirectservice.model.domainObject.ShortLink;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

/**
 * Converts a {@link Link} DAO into its {@link ShortLink} domain representation, including the
 * epoch-seconds-to-{@link Instant} conversion {@code expiresAt} requires.
 */
@Slf4j
@Component
public class LinkDbMapper {

	/**
	 * Converts a {@link Link} DAO into its {@link ShortLink} domain representation.
	 *
	 * @param entity the DAO instance to convert.
	 * @return the equivalent {@link ShortLink}.
	 * @throws InvalidConversionInputException if {@code entity} is {@code null}.
	 */
	public ShortLink toDomain(final Link entity) {
		if (entity == null) {
			log.atError()
					.addKeyValue("reason", "Link not provided")
					.log("DAO to Domain Model conversion failed");
			throw new InvalidConversionInputException("DAO Model not provided for conversion");
		}

		final Instant expiresAt = Optional.ofNullable(entity.expiresAt())
				.map(Instant::ofEpochSecond)
				.orElse(null);

		return ShortLink.builder()
				.shortCode(entity.shortCode())
				.longUrl(entity.longUrl())
				.expiresAt(expiresAt)
				.status(entity.status())
				.build();
	}

}
