package com.roczyno.aws.task_manager.model;

import software.amazon.awssdk.enhanced.dynamodb.AttributeConverter;
import software.amazon.awssdk.enhanced.dynamodb.AttributeValueType;
import software.amazon.awssdk.enhanced.dynamodb.EnhancedType;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

public class StatusConverter implements AttributeConverter<Status> {

	@Override
	public AttributeValue transformFrom(Status status) {
		if (status == null) {
			return AttributeValue.builder().nul(true).build();
		}
		return AttributeValue.builder().s(status.name()).build();
	}

	@Override
	public Status transformTo(AttributeValue attributeValue) {
		if (attributeValue == null || attributeValue.s() == null) {
			return null;
		}
		return Status.valueOf(attributeValue.s());
	}

	@Override
	public EnhancedType<Status> type() {
		return EnhancedType.of(Status.class);
	}

	@Override
	public AttributeValueType attributeValueType() {
		return AttributeValueType.S;
	}
}
