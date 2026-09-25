package io.urlshortener.redirectservice.exception;

import lombok.Getter;

/**
 * Thrown when a link exists for a given short code but has already passed its {@code expiresAt}.
 *
 * <p>Kept distinct from {@link ShortLinkNotFoundException} - unlike url-service, which treats
 * "not found" and "expired" identically for its own GET, redirect-service needs to tell them apart
 * so the eventual {@code ClickEvent} (see ADR-0003) can record the correct
 * {@link io.urlshortener.eventcontracts.ClickOutcome ClickOutcome}.
 */
@Getter
public class ShortLinkExpiredException extends RuntimeException {

	/**
	 * The short code of the link that has expired.
	 */
	private final String shortCode;

	/**
	 * Creates the exception for a short code that resolved to an expired link.
	 *
	 * @param shortCode the short code of the expired link.
	 */
	public ShortLinkExpiredException(final String shortCode) {
		super("Short Link: %s has expired".formatted(shortCode));
		this.shortCode = shortCode;
	}

}
