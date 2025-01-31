package com.roczyno.aws.task_manager;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sns.model.PublishRequest;

import java.util.HashMap;
import java.util.Map;

public class TaskNotificationHandler implements RequestHandler<SQSEvent, Void> {
	private final SnsClient snsClient = SnsClient.builder().build();
	private final String SNS_TOPIC_ARN = System.getenv("ASSIGNMENT_TOPIC_ARN");
	private final ObjectMapper objectMapper = new ObjectMapper();

	@Override
	public Void handleRequest(SQSEvent event, Context context) {
		for (SQSEvent.SQSMessage message : event.getRecords()) {
			try {
				// Parse the message body
				Map<String, String> messageData = objectMapper.readValue(
						message.getBody(),
						new TypeReference<Map<String, String>>() {
						}
				);


				// Create SNS message attributes for filtering
				Map<String, MessageAttributeValue> messageAttributes = new HashMap<>();
				messageAttributes.put("assignedUserId", MessageAttributeValue.builder()
						.dataType("String")
						.stringValue(messageData.get("assignedUserId"))
						.build());

				messageAttributes.put("notificationType", MessageAttributeValue.builder()
						.dataType("String")
						.stringValue(messageData.get("notificationType"))
						.build());

				// Publish to SNS
				PublishRequest publishRequest = PublishRequest.builder()
						.topicArn(SNS_TOPIC_ARN)
						.message(formatEmailMessage(messageData))
						.messageAttributes(messageAttributes)
						.build();

				snsClient.publish(publishRequest);

			} catch (Exception e) {
				context.getLogger().log("Error processing message: " + e.getMessage());
				throw new RuntimeException("Failed to process message", e);
			}
		}
		return null;
	}

	private String formatEmailMessage(Map<String, String> messageData) {
		return String.format(
				"Task Assignment Notification\n" +
						"Task Name: %s\n" +
						"Description: %s\n" +
						"Assigned User: %s\n" +
						"Deadline: %s",
				messageData.get("taskName"),
				messageData.get("description"),
				messageData.get("assignedUserName"),
				messageData.get("deadline")
		);
	}
}
