package io.urlshortener.redirectservice.repository;

import io.urlshortener.redirectservice.model.domainObject.ShortLink;

import java.util.Optional;

/**
 * Persistence port for {@link ShortLink}s.
 */
public interface LinkRepository {

	/**
	 * Looks up a link by its short code.
	 *
	 * @param shortCode the short code to look up.
	 * @return the matching link, or {@link Optional#empty()} if no link exists for that short code.
	 */
	Optional<ShortLink> findByShortCode(final String shortCode);

}
