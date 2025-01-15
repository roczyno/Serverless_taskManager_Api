package com.roczyno.aws.task_manager.model;

import software.amazon.awssdk.enhanced.dynamodb.AttributeConverter;
import software.amazon.awssdk.enhanced.dynamodb.AttributeValueType;
import software.amazon.awssdk.enhanced.dynamodb.EnhancedType;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class LocalDateTimeConverter implements AttributeConverter<LocalDateTime> {

	// Define the ISO-8601 format to use for the string representation
	private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

	@Override
	public AttributeValue transformFrom(LocalDateTime localDateTime) {
		if (localDateTime == null) {
			return AttributeValue.builder().nul(true).build();  // Handle null values
		}
		// Convert LocalDateTime to String in ISO format
		return AttributeValue.builder().s(localDateTime.format(FORMATTER)).build();
	}

	@Override
	public LocalDateTime transformTo(AttributeValue attributeValue) {
		if (attributeValue == null || attributeValue.s() == null) {
			return null;  // Handle null or missing values
		}
		// Convert String back to LocalDateTime using the same formatter
		return LocalDateTime.parse(attributeValue.s(), FORMATTER);
	}

	@Override
	public EnhancedType<LocalDateTime> type() {
		return EnhancedType.of(LocalDateTime.class);  // Return the type this converter is handling
	}

	@Override
	public AttributeValueType attributeValueType() {
		return AttributeValueType.S;  // AttributeValueType.S for string type
	}
}
