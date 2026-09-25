package io.urlshortener.redirectservice.mapper.dbMapper;

import io.urlshortener.linkscontract.Link;
import io.urlshortener.redirectservice.exception.CorruptedDataException;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LinkAttributeMapperTest {

	private final LinkAttributeMapper mapper = new LinkAttributeMapper();

	@Test
	void toObject_shouldThrowCorruptedDataException_whenLongUrlIsMissing() {
		// Arrange
		final Map<String, AttributeValue> attributeMap = new HashMap<>();
		attributeMap.put("shortCode", AttributeValue.builder().s("abc1234").build());

		// Act & Assert
		assertThatThrownBy(() -> mapper.toObject(attributeMap))
				.isInstanceOf(CorruptedDataException.class);
	}

	@Test
	void toObject_shouldDefaultCreatedAtToZero_whenCreatedAtIsMissing() {
		// Arrange
		final Map<String, AttributeValue> attributeMap = new HashMap<>();
		attributeMap.put("shortCode", AttributeValue.builder().s("abc1234").build());
		attributeMap.put("longUrl", AttributeValue.builder().s("https://example.com").build());

		// Act
		final Link link = mapper.toObject(attributeMap);

		// Assert
		assertThat(link.createdAt()).isZero();
	}

	@Test
	void toObject_shouldReadAllFields_whenAttributeMapHasEveryFieldPresent() {
		// Arrange
		final Map<String, AttributeValue> attributeMap = new HashMap<>();
		attributeMap.put("shortCode", AttributeValue.builder().s("abc1234").build());
		attributeMap.put("longUrl", AttributeValue.builder().s("https://example.com").build());
		attributeMap.put("ownerId", AttributeValue.builder().s("owner-1").build());
		attributeMap.put("createdAt", AttributeValue.builder().n("1000").build());
		attributeMap.put("expiresAt", AttributeValue.builder().n("2000").build());
		attributeMap.put("managementTokenHash", AttributeValue.builder().s("hash").build());
		attributeMap.put("status", AttributeValue.builder().s("FLAGGED").build());

		// Act
		final Link link = mapper.toObject(attributeMap);

		// Assert
		assertThat(link.shortCode()).isEqualTo("abc1234");
		assertThat(link.longUrl()).isEqualTo("https://example.com");
		assertThat(link.ownerId()).isEqualTo("owner-1");
		assertThat(link.createdAt()).isEqualTo(1000L);
		assertThat(link.expiresAt()).isEqualTo(2000L);
		assertThat(link.managementTokenHash()).isEqualTo("hash");
		assertThat(link.status()).isEqualTo("FLAGGED");
	}

	@Test
	void toObject_shouldLeaveOptionalFieldsNull_whenAttributeMapHasThemAbsent() {
		// Arrange
		final Map<String, AttributeValue> attributeMap = new HashMap<>();
		attributeMap.put("shortCode", AttributeValue.builder().s("abc1234").build());
		attributeMap.put("longUrl", AttributeValue.builder().s("https://example.com").build());
		attributeMap.put("createdAt", AttributeValue.builder().n("1000").build());

		// Act
		final Link link = mapper.toObject(attributeMap);

		// Assert
		assertThat(link.ownerId()).isNull();
		assertThat(link.expiresAt()).isNull();
		assertThat(link.managementTokenHash()).isNull();
		assertThat(link.status()).isNull();
	}

	@Test
	void createKeyAttribute_shouldContainOnlyShortCode_whenGivenAShortCode() {
		// Arrange
		// (shortCode literal used directly below)

		// Act
		final Map<String, AttributeValue> keyMap = mapper.createKeyAttribute("abc1234");

		// Assert
		assertThat(keyMap).hasSize(1);
		assertThat(keyMap.get("shortCode").s()).isEqualTo("abc1234");
	}

}
