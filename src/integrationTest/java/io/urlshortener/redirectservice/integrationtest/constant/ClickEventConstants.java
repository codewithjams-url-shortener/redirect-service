package io.urlshortener.redirectservice.integrationtest.constant;

/**
 * JSON field names of a {@code ClickEvent} as it appears on the {@code click-events} SQS queue, mirroring
 * {@code ClickEvent}'s record component names.
 *
 * <p>
 *     Handled as raw JSON ({@code JsonNode}) rather than the {@code ClickEvent} record itself, so this suite has no
 *     compile-time dependency on event-contracts. It verifies the wire contract actually published, not compile-time
 *     type compatibility with redirect-service's internals.
 * </p>
 */
public class ClickEventConstants {

	/**
	 * Name of the {@code shortCode} field.
	 */
	public static final String FIELD_SHORT_CODE = "shortCode";

	/**
	 * Name of the {@code outcome} field.
	 */
	public static final String FIELD_OUTCOME = "outcome";

	/**
	 * The {@code outcome} value published for a successful redirect.
	 */
	public static final String OUTCOME_RESOLVED = "RESOLVED";

	/**
	 * The {@code outcome} value published when no link exists for the requested short code.
	 */
	public static final String OUTCOME_NOT_FOUND = "NOT_FOUND";

	/**
	 * The {@code outcome} value published when a link exists but has expired.
	 */
	public static final String OUTCOME_EXPIRED = "EXPIRED";

	/**
	 * Not instantiable.
	 */
	private ClickEventConstants() {
	}

}
