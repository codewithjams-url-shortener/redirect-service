package io.urlshortener.redirectservice.service;

import io.urlshortener.redirectservice.exception.ShortLinkExpiredException;
import io.urlshortener.redirectservice.exception.ShortLinkNotFoundException;
import io.urlshortener.redirectservice.model.domainObject.ShortLink;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolves a short code to its destination URL for a redirect attempt.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedirectService {

	private final LinkLookupService linkLookupService;

	/**
	 * Resolves a short code to its destination URL.
	 *
	 * @param shortCode the short code to resolve.
	 * @return the destination URL to redirect to.
	 * @throws ShortLinkNotFoundException if no link exists for {@code shortCode}.
	 * @throws ShortLinkExpiredException  if a link exists for {@code shortCode} but has already expired.
	 */
	public String resolve(final String shortCode) {
		final Optional<ShortLink> link = linkLookupService.getLink(shortCode);
		if (link.isEmpty()) {
			throw new ShortLinkNotFoundException(shortCode);
		}
		final ShortLink shortLink = link.get();
		final Instant expiresAt = shortLink.expiresAt();
		if (Objects.nonNull(expiresAt) && Instant.now().isAfter(expiresAt)) {
			throw new ShortLinkExpiredException(shortCode);
		}
		return shortLink.longUrl();
	}

}
