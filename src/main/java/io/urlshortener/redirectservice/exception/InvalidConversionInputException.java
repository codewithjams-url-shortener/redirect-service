package io.urlshortener.redirectservice.exception;

/**
 * Thrown when a mapper is asked to convert a required input that is missing (e.g. {@code null}).
 */
public class InvalidConversionInputException extends RuntimeException {

	/**
	 * Creates the exception with a message describing what input was missing.
	 *
	 * @param message description of the missing input.
	 */
	public InvalidConversionInputException(String message) {
		super(message);
	}

}
