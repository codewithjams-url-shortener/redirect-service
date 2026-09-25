package io.urlshortener.redirectservice.model.cache;

import io.urlshortener.redirectservice.model.domainObject.ShortLink;

/**
 * The value stored in Redis for a short code lookup - either a genuine hit, or a negative-cache
 * entry recording that DynamoDB already confirmed the short code doesn't exist, so it's not worth
 * checking again until this entry's TTL expires.
 *
 * @param found {@code true} if {@link link} is a genuine DynamoDB hit, {@code false} for a
 *              negative-cache entry (in which case {@link link} is {@code null}).
 * @param link  the resolved {@link ShortLink}, or {@code null} for a negative-cache entry.
 */
public record CachedLink(boolean found, ShortLink link) {
}
