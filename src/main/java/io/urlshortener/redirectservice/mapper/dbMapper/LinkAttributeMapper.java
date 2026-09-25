package io.urlshortener.redirectservice.mapper.dbMapper;

import io.urlshortener.linkscontract.Link;
import io.urlshortener.redirectservice.exception.CorruptedDataException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.HashMap;
import java.util.Map;

/**
 * Converts Raw {@link AttributeValue} map used by the DynamoDB SDK to {@link Link} DAOs format.
 */
@Slf4j
@Component
public class LinkAttributeMapper {

	/**
	 * Converts a raw DynamoDB item back into a {@link Link}. Only {@code longUrl} is validated as
	 * required - it's the one attribute redirect-service actually depends on to do its job, unlike
	 * e.g. {@code createdAt}, which this service never reads and which defaults to {@code 0} if
	 * absent rather than failing.
	 *
	 * @param map the raw DynamoDB item.
	 * @return the equivalent {@link Link}.
	 * @throws CorruptedDataException if the required {@code longUrl} attribute is missing.
	 */
	public Link toObject(final Map<String, AttributeValue> map) {
		final String shortCode = map.containsKey("shortCode") ? map.get("shortCode").s() : null;

		if (!map.containsKey("longUrl")) {
			log.atError()
					.addKeyValue("field", "longUrl")
					.log("Required value missing from DB");
			throw new CorruptedDataException("Expecting value at longUrl, value is absent instead");
		}
		final String longUrl = map.get("longUrl").s();

		final String ownerId = map.containsKey("ownerId") ? map.get("ownerId").s() : null;
		final long createdAt = map.containsKey("createdAt") ? Long.parseLong(map.get("createdAt").n()) : 0L;
		final Long expiresAt = map.containsKey("expiresAt") ? Long.parseLong(map.get("expiresAt").n()) : null;
		final String managementTokenHash = map.containsKey("managementTokenHash") ? map.get("managementTokenHash").s() :
				null;
		final String status = map.containsKey("status") ? map.get("status").s() : null;
		return new Link(shortCode, longUrl, ownerId, createdAt, expiresAt, managementTokenHash, status);
	}

	/**
	 * Builds the DynamoDB key attribute map identifying a link by its short code (the table's
	 * partition key), for use in a {@code GetItem} request.
	 *
	 * @param shortCode the short code to build a key for.
	 * @return a single-entry attribute-value map suitable as a DynamoDB item key.
	 */
	public Map<String, AttributeValue> createKeyAttribute(final String shortCode) {
		final Map<String, AttributeValue> map = new HashMap<>();
		map.put("shortCode", AttributeValue.builder().s(shortCode).build());
		return map;
	}

}
