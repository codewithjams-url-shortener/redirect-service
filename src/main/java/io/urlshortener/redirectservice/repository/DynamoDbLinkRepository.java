package io.urlshortener.redirectservice.repository;

import io.urlshortener.linkscontract.Link;
import io.urlshortener.redirectservice.constant.AwsConstants;
import io.urlshortener.redirectservice.mapper.dbMapper.LinkAttributeMapper;
import io.urlshortener.redirectservice.mapper.dbMapper.LinkDbMapper;
import io.urlshortener.redirectservice.model.domainObject.ShortLink;
import io.urlshortener.redirectservice.property.AwsProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;

import java.util.Optional;

/**
 * {@link LinkRepository} implementation backed by DynamoDB, using the raw SDK client to read Short Code URLs.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class DynamoDbLinkRepository implements LinkRepository {

	/**
	 * Raw DynamoDB SDK client used to issue write.
	 */
	private final DynamoDbClient dynamoDbClient;

	/**
	 * Source of the configured DynamoDB table name.
	 */
	private final AwsProperties awsProperties;

	/**
	 * Converts between the {@link ShortLink} domain object and the {@code Link} DAO.
	 */
	private final LinkDbMapper linkDbMapper;

	/**
	 * Converts the {@code Link} DAO to and from a DynamoDB attribute-value map.
	 */
	private final LinkAttributeMapper linkAttributeMapper;

	/**
	 * Looks up a link by its short code.
	 *
	 * @param shortCode the short code to look up.
	 * @return the matching link, or {@link Optional#empty()} if no item exists for that short code.
	 */
	@Override
	public Optional<ShortLink> findByShortCode(final String shortCode) {
		final GetItemRequest readRequest = GetItemRequest.builder()
				.tableName(awsProperties.getDynamoDb().getTables().get(AwsConstants.TABLE_LINKS))
				.key(linkAttributeMapper.createKeyAttribute(shortCode))
				.build();
		final GetItemResponse response = dynamoDbClient.getItem(readRequest);
		if (!response.hasItem()) {
			return Optional.empty();
		}
		final Link link = linkAttributeMapper.toObject(response.item());
		return Optional.of(linkDbMapper.toDomain(link));
	}

}
