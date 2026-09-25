package io.urlshortener.redirectservice.constant;

/**
 * Keys used to look up configured resource names, e.g. from {@code aws.dynamo-db.tables}.
 */
public class AwsConstants {

	/**
	 * Key identifying the DynamoDB table storing {@link io.urlshortener.linkscontract.Link Link} items.
	 */
	public static final String TABLE_LINKS = "links-table";

	/**
	 * Key identifying the SNS topic {@link io.urlshortener.eventcontracts.ClickEvent ClickEvent}s are published to.
	 */
	public static final String TOPIC_CLICK_EVENTS = "click-events-topic";

	/**
	 * Not instantiable.
	 */
	private AwsConstants() {
	}

}
