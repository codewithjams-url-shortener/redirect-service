package io.urlshortener.redirectservice.property;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * Binds the {@code aws.*} configuration properties used to construct the AWS SDK beans in
 * {@link io.urlshortener.redirectservice.config.AwsConfig AwsConfig}.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "aws")
public class AwsProperties {

	/**
	 * AWS region to target, e.g. {@code us-east-1}.
	 */
	private String region;

	/**
	 * Static credentials to authenticate with.
	 */
	private Credential credential;

	/**
	 * DynamoDB-specific configuration.
	 */
	private DynamoDb dynamoDb;

	/**
	 * SNS-specific configuration.
	 */
	private Sns sns;

	/**
	 * Static AWS credentials, configured directly rather than resolved via the default provider chain.
	 */
	@Getter
	@Setter
	public static class Credential {

		/**
		 * AWS access key ID.
		 */
		private String accessKey;

		/**
		 * AWS secret access key.
		 */
		private String secretKey;

	}

	/**
	 * DynamoDB-specific configuration: an optional local endpoint override, and the map of logical table names (see
	 * {@link io.urlshortener.redirectservice.constant.AwsConstants AwsConstants}) to their actual configured table
	 * names.
	 */
	@Getter
	@Setter
	public static class DynamoDb {

		/**
		 * Local DynamoDB endpoint to target instead of the real AWS endpoint, if set (e.g. floci).
		 */
		private String endpointOverride;

		/**
		 * Maps logical table keys (see {@link io.urlshortener.redirectservice.constant.AwsConstants AwsConstants}) to
		 * actual table names.
		 */
		private Map<String, String> tables;

	}

	/**
	 * SNS-specific configuration: an optional local endpoint override, and the map of logical topic names (see
	 * {@link io.urlshortener.redirectservice.constant.AwsConstants AwsConstants}) to their actual configured topic
	 * ARNs.
	 */
	@Getter
	@Setter
	public static class Sns {

		/**
		 * Local SNS endpoint to target instead of the real AWS endpoint, if set (e.g. floci).
		 */
		private String endpointOverride;

		/**
		 * Maps logical topic keys (see {@link io.urlshortener.redirectservice.constant.AwsConstants AwsConstants}) to
		 * actual topic ARNs.
		 */
		private Map<String, String> topics;

	}

}
