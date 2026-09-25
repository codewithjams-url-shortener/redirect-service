package io.urlshortener.redirectservice.controller;

import io.urlshortener.redirectservice.service.RedirectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@Slf4j
@RestController
@RequiredArgsConstructor
public class RedirectController implements RedirectApi {

	private final RedirectService redirectService;

	@Override
	public ResponseEntity<Void> redirect(final String shortCode) {
		final String longUrl = redirectService.resolve(shortCode);
		// 302, never 301 - the destination URL is editable via url-service's PATCH, so this must
		// never be permanently cached by the client.
		return ResponseEntity.status(HttpStatus.FOUND)
				.location(URI.create(longUrl))
				.build();
	}

}
