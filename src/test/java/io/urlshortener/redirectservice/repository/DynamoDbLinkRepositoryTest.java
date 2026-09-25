package io.urlshortener.redirectservice.repository;

import io.urlshortener.linkscontract.Link;
import io.urlshortener.redirectservice.constant.AwsConstants;
import io.urlshortener.redirectservice.mapper.dbMapper.LinkAttributeMapper;
import io.urlshortener.redirectservice.mapper.dbMapper.LinkDbMapper;
import io.urlshortener.redirectservice.model.domainObject.ShortLink;
import io.urlshortener.redirectservice.property.AwsProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DynamoDbLinkRepositoryTest {

	@Mock
	private DynamoDbClient dynamoDbClient;

	@Mock
	private AwsProperties awsProperties;

	@Mock
	private AwsProperties.DynamoDb dynamoDbProperties;

	@Mock
	private LinkDbMapper linkDbMapper;

	@Mock
	private LinkAttributeMapper linkAttributeMapper;

	private DynamoDbLinkRepository repository;

	@BeforeEach
	void setUp() {
		repository = new DynamoDbLinkRepository(dynamoDbClient, awsProperties, linkDbMapper, linkAttributeMapper);
		given(awsProperties.getDynamoDb()).willReturn(dynamoDbProperties);
		given(dynamoDbProperties.getTables()).willReturn(Map.of(AwsConstants.TABLE_LINKS, "Links"));
	}

	@Test
	void findByShortCode_shouldReturnEmpty_whenNoItemExistsForShortCode() {
		// Arrange
		final Map<String, AttributeValue> key = Map.of("shortCode", AttributeValue.builder().s("abc1234").build());
		given(linkAttributeMapper.createKeyAttribute("abc1234")).willReturn(key);
		given(dynamoDbClient.getItem(any(GetItemRequest.class)))
				.willReturn(GetItemResponse.builder().build());

		// Act
		final Optional<ShortLink> result = repository.findByShortCode("abc1234");

		// Assert
		assertThat(result).isEmpty();
	}

	@Test
	void findByShortCode_shouldReturnMappedLink_whenItemExistsForShortCode() {
		// Arrange
		final Map<String, AttributeValue> key = Map.of("shortCode", AttributeValue.builder().s("abc1234").build());
		final Map<String, AttributeValue> item = Map.of(
				"shortCode", AttributeValue.builder().s("abc1234").build(),
				"longUrl", AttributeValue.builder().s("https://example.com").build()
		);
		final Link entity = new Link("abc1234", "https://example.com", null, 1_000L, null, "hash", null);
		final ShortLink domain = ShortLink.builder().shortCode("abc1234").longUrl("https://example.com").build();

		given(linkAttributeMapper.createKeyAttribute("abc1234")).willReturn(key);
		given(dynamoDbClient.getItem(any(GetItemRequest.class)))
				.willReturn(GetItemResponse.builder().item(item).build());
		given(linkAttributeMapper.toObject(item)).willReturn(entity);
		given(linkDbMapper.toDomain(entity)).willReturn(domain);

		// Act
		final Optional<ShortLink> result = repository.findByShortCode("abc1234");

		// Assert
		assertThat(result).contains(domain);
	}

	@Test
	void findByShortCode_shouldRequestTheConfiguredTableName_whenLookingUpALink() {
		// Arrange
		given(linkAttributeMapper.createKeyAttribute("abc1234"))
				.willReturn(Map.of("shortCode", AttributeValue.builder().s("abc1234").build()));
		given(dynamoDbClient.getItem(any(GetItemRequest.class)))
				.willReturn(GetItemResponse.builder().build());

		// Act
		repository.findByShortCode("abc1234");

		// Assert
		final var requestCaptor = org.mockito.ArgumentCaptor.forClass(GetItemRequest.class);
		verify(dynamoDbClient).getItem(requestCaptor.capture());
		assertThat(requestCaptor.getValue().tableName()).isEqualTo("Links");
	}

}
