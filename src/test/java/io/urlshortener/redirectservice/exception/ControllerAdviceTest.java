package io.urlshortener.redirectservice.exception;

import io.urlshortener.redirectservice.model.dataTransferObject.ErrorResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ControllerAdviceTest {

	private final ControllerAdvice controllerAdvice = new ControllerAdvice();

	@Test
	void handleConstraintViolationError_shouldReturn400_whenPathParameterFailsValidation() {
		// Arrange
		final ConstraintViolationException exception = constraintViolationException(
				constraintViolation("redirect.shortCode", "size must be between 3 and 2147483647")
		);

		// Act
		final ResponseEntity<ErrorResponse> response = controllerAdvice.handleConstraintViolationError(exception);

		// Assert
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void handleConstraintViolationError_shouldStripMethodNamePrefixFromField_whenPropertyPathIncludesMethodName() {
		// Arrange
		final ConstraintViolationException exception = constraintViolationException(
				constraintViolation("redirect.shortCode", "size must be between 3 and 2147483647")
		);

		// Act
		final ResponseEntity<ErrorResponse> response = controllerAdvice.handleConstraintViolationError(exception);

		// Assert
		assertNotNull(response.getBody());
		assertThat(response.getBody().getMessage()).isEqualTo("shortCode: size must be between 3 and 2147483647");
	}

	@Test
	void handleConstraintViolationError_shouldJoinAllViolations_whenMultipleParametersFailValidation() {
		// Arrange
		final ConstraintViolationException exception = constraintViolationException(
				constraintViolation("redirect.shortCode", "size must be between 3 and 2147483647"),
				constraintViolation("redirect.other", "must not be blank")
		);

		// Act
		final ResponseEntity<ErrorResponse> response = controllerAdvice.handleConstraintViolationError(exception);

		// Assert
		// ConstraintViolationException does not preserve insertion order of its violations, so this
		// asserts both entries are present rather than a fixed concatenation order.
		assertNotNull(response.getBody());
		assertThat(response.getBody().getMessage())
				.contains("shortCode: size must be between 3 and 2147483647")
				.contains("other: must not be blank")
				.contains(", ");
	}

	@Test
	void handleNotFound_shouldReturn404_whenShortLinkIsNotFound() {
		// Arrange
		final ShortLinkNotFoundException exception = new ShortLinkNotFoundException("missing");

		// Act
		final ResponseEntity<ErrorResponse> response = controllerAdvice.handleNotFound(exception);

		// Assert
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void handleNotFound_shouldReturnExceptionMessage_whenShortLinkIsNotFound() {
		// Arrange
		final ShortLinkNotFoundException exception = new ShortLinkNotFoundException("missing");

		// Act
		final ResponseEntity<ErrorResponse> response = controllerAdvice.handleNotFound(exception);

		// Assert
		assertNotNull(response.getBody());
		assertThat(response.getBody().getMessage()).isEqualTo("Short Link: missing not found");
	}

	@Test
	void handleExpired_shouldReturn404_whenShortLinkHasExpired() {
		// Arrange
		final ShortLinkExpiredException exception = new ShortLinkExpiredException("abc1234");

		// Act
		final ResponseEntity<ErrorResponse> response = controllerAdvice.handleExpired(exception);

		// Assert
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void handleExpired_shouldReturnExceptionMessage_whenShortLinkHasExpired() {
		// Arrange
		final ShortLinkExpiredException exception = new ShortLinkExpiredException("abc1234");

		// Act
		final ResponseEntity<ErrorResponse> response = controllerAdvice.handleExpired(exception);

		// Assert
		assertNotNull(response.getBody());
		assertThat(response.getBody().getMessage()).isEqualTo("Short Link: abc1234 has expired");
	}

	private ConstraintViolationException constraintViolationException(final ConstraintViolation<?>... violations) {
		final Set<ConstraintViolation<?>> violationSet = new LinkedHashSet<>(Arrays.asList(violations));
		return new ConstraintViolationException(violationSet);
	}

	@SuppressWarnings("unchecked")
	private ConstraintViolation<?> constraintViolation(final String propertyPath, final String message) {
		final ConstraintViolation<Object> violation = mock(ConstraintViolation.class);
		final Path path = mock(Path.class);
		when(path.toString()).thenReturn(propertyPath);
		when(violation.getPropertyPath()).thenReturn(path);
		when(violation.getMessage()).thenReturn(message);
		return violation;
	}

}
