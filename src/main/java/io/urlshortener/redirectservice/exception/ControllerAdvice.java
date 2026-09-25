package io.urlshortener.redirectservice.exception;

import io.urlshortener.redirectservice.model.dataTransferObject.ErrorResponse;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * Translates domain exceptions and request validation failures raised by controllers into
 * structured HTTP error responses.
 */
@Slf4j
@RestControllerAdvice
public class ControllerAdvice {

	/**
	 * Maps a path parameter validation failure (e.g. shortCode shorter than the OpenAPI-declared
	 * minLength) to a structured error response instead of Spring's default error body.
	 *
	 * @param e the validation exception raised when a path parameter fails bean validation.
	 * @return a {@code 400 Bad Request} response listing each invalid parameter and its violation.
	 */
	@ExceptionHandler(ConstraintViolationException.class)
	public ResponseEntity<ErrorResponse> handleConstraintViolationError(final ConstraintViolationException e) {
		final String message = e.getConstraintViolations()
				.stream()
				.map(
						violation -> {
							final String propertyPath = violation.getPropertyPath().toString();
							final String field = propertyPath.substring(propertyPath.lastIndexOf('.') + 1);
							return "%s: %s".formatted(field, violation.getMessage());
						}
				)
				.collect(Collectors.joining(", "));
		log.atError()
				.addKeyValue("reason", message)
				.log("Request validation failed due to Input Constraint Violation");
		final ErrorResponse response = ErrorResponse.builder().message(message).build();
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
	}

	/**
	 * Maps a missing link to a not-found response.
	 *
	 * @param e the not-found exception raised by a controller.
	 * @return a {@code 404 Not Found} response carrying the exception's message.
	 */
	@ExceptionHandler(ShortLinkNotFoundException.class)
	public ResponseEntity<ErrorResponse> handleNotFound(final ShortLinkNotFoundException e) {
		log.atError()
				.addKeyValue("shortCode", e.getShortCode())
				.log("Short Code not found");
		final ErrorResponse response = ErrorResponse.builder().message(e.getMessage()).build();
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
	}

	/**
	 * Maps an expired link to a not-found response.
	 *
	 * @param e the expiry exception raised by a controller.
	 * @return a {@code 404 Not Found} response carrying the exception's message.
	 */
	@ExceptionHandler(ShortLinkExpiredException.class)
	public ResponseEntity<ErrorResponse> handleExpired(final ShortLinkExpiredException e) {
		log.atError()
				.addKeyValue("shortCode", e.getShortCode())
				.log("Short Code expired");
		final ErrorResponse response = ErrorResponse.builder().message(e.getMessage()).build();
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
	}

}
