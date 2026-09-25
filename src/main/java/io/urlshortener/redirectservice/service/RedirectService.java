package io.urlshortener.redirectservice.service;

import io.urlshortener.eventcontracts.ClickOutcome;
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

	/**
	 * Looks up a link by its short code, cache-aside against DynamoDB.
	 */
	private final LinkLookupService linkLookupService;

	/**
	 * Publishes a {@code ClickEvent} for each redirect attempt, regardless of outcome.
	 */
	private final ClickEventPublisher clickEventPublisher;

	/**
	 * Resolves a short code to its destination URL, publishing a {@code ClickEvent} recording the
	 * outcome of the attempt regardless of whether it succeeds.
	 *
	 * @param shortCode     the short code to resolve.
	 * @param refererDomain the domain of the HTTP {@code Referer} header, or {@code null} for direct traffic.
	 * @param userAgentRaw  the raw {@code User-Agent} header of the requesting client.
	 * @param ipHash        a one-way hash of the requesting client's IP address.
	 * @return the destination URL to redirect to.
	 * @throws ShortLinkNotFoundException if no link exists for {@code shortCode}.
	 * @throws ShortLinkExpiredException  if a link exists for {@code shortCode} but has already expired.
	 */
	public String resolve(final String shortCode, final String refererDomain, final String userAgentRaw,
						  final String ipHash) {
		final Optional<ShortLink> link = linkLookupService.getLink(shortCode);
		if (link.isEmpty()) {
			clickEventPublisher.publish(shortCode, ClickOutcome.NOT_FOUND, refererDomain, userAgentRaw, ipHash);
			throw new ShortLinkNotFoundException(shortCode);
		}
		final ShortLink shortLink = link.get();
		final Instant expiresAt = shortLink.expiresAt();
		if (Objects.nonNull(expiresAt) && Instant.now().isAfter(expiresAt)) {
			clickEventPublisher.publish(shortCode, ClickOutcome.EXPIRED, refererDomain, userAgentRaw, ipHash);
			throw new ShortLinkExpiredException(shortCode);
		}
		clickEventPublisher.publish(shortCode, ClickOutcome.RESOLVED, refererDomain, userAgentRaw, ipHash);
		return shortLink.longUrl();
	}

}
