package io.urlshortener.redirectservice.integrationtest.config;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Writes a link item holding nothing but its key into the Links table. redirect-service requires every link to carry a
 * long URL, so looking such an item up is answered with a 500: the one way to provoke a genuine server error
 * from outside, with no test-only endpoint in the application.
 *
 * <p>
 *     Like the rest of this source set it knows nothing of redirect-service's own classes,
 *     only the table's name and key.
 * </p>
 */
public class CorruptLinkSeeder {

	private final Supplier<DynamoDbClient> clientFactory;

	private final String tableName;

	private final String partitionKey;

	public CorruptLinkSeeder(final Supplier<DynamoDbClient> clientFactory, final String tableName,
							 final String partitionKey) {
		this.clientFactory = clientFactory;
		this.tableName = tableName;
		this.partitionKey = partitionKey;
	}

	/**
	 * Plants a fresh corrupt link.
	 *
	 * @return its short code, which {@code GET /{shortCode}} answers with a 500.
	 */
	public String seed() {
		final String shortCode = "corrupt-%s".formatted(UUID.randomUUID().toString().substring(0, 8));
		try (final DynamoDbClient client = clientFactory.get()) {
			client.putItem(PutItemRequest.builder()
					.tableName(tableName)
					.item(Map.of(partitionKey, AttributeValue.builder().s(shortCode).build()))
					.build());
		}
		return shortCode;
	}

}
