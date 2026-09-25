package io.urlshortener.redirectservice.model.domainObject;

import lombok.Builder;

import java.time.Instant;

/**
 * Domain object representing a shortened link, independent of both the OpenAPI-generated DTOs and the DynamoDB
 * {@link io.urlshortener.linkscontract.Link Link} DAO. Immutable - redirect-service only ever reads a link,
 * never mutates it.
 *
 * @param shortCode the link's short code.
 * @param longUrl   the original, full-length URL this short link redirects to.
 * @param expiresAt when this link expires and should no longer resolve, if set.
 * @param status    the link's current lifecycle status.
 */
@Builder
public record ShortLink(
		String shortCode,
		String longUrl,
		Instant expiresAt,
		String status
) {
}
