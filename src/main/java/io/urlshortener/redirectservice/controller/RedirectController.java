package io.urlshortener.redirectservice.controller;

import io.urlshortener.redirectservice.hash.IpHasher;
import io.urlshortener.redirectservice.service.RedirectService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * REST controller implementing the {@code /{shortCode}} API contract defined in {@code redirect.yaml}.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class RedirectController implements RedirectApi {

	/**
	 * Orchestrates the actual short-code resolution and click-event publishing.
	 */
	private final RedirectService redirectService;

	/**
	 * The current request, injected as a thread-safe, request-scoped proxy by Spring.
	 */
	private final HttpServletRequest request;

	/**
	 * Resolves a short code and redirects to its destination URL.
	 *
	 * @param shortCode the short code to resolve.
	 * @return {@code 302 Found} pointing at the destination URL.
	 */
	@Override
	public ResponseEntity<Void> redirect(final String shortCode) {
		final String refererDomain = extractRefererDomain(request.getHeader("Referer"));
		final String userAgentRaw = request.getHeader("User-Agent");
		// getRemoteAddr() is the direct TCP peer - if redirect-service ever sits behind a load
		// balancer/proxy, this would need X-Forwarded-For handling instead. No such layer exists per
		// the current ADRs, so this is deliberate, not an oversight - revisit if that changes.
		final String ipHash = IpHasher.hash(request.getRemoteAddr());
		final String longUrl = redirectService.resolve(shortCode, refererDomain, userAgentRaw, ipHash);
		// 302, never 301 - the destination URL is editable via url-service's PATCH, so this must
		// never be permanently cached by the client.
		return ResponseEntity.status(HttpStatus.FOUND)
				.location(URI.create(longUrl))
				.build();
	}

	/**
	 * Extracts the domain from a raw {@code Referer} header value.
	 *
	 * @param refererHeader the raw {@code Referer} header value, may be {@code null}.
	 * @return the header's domain, or {@code null} if the header was absent or malformed.
	 */
	private String extractRefererDomain(final String refererHeader) {
		if (!StringUtils.hasText(refererHeader)) {
			return null;
		}
		try {
			return URI.create(refererHeader).getHost();
		} catch (IllegalArgumentException e) {
			log.atWarn().addKeyValue("referer", refererHeader).log("Malformed Referer header, ignoring");
			return null;
		}
	}

}
