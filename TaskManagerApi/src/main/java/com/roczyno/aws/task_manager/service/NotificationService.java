
package com.roczyno.aws.task_manager.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.roczyno.aws.task_manager.model.CreateTaskRequest;
import com.roczyno.aws.task_manager.model.Task;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.SnsException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class NotificationService {
	private final SqsClient sqsClient;
	private final ObjectMapper objectMapper;
	private final SnsClient snsClient;



	public void queueTaskAssignmentNotification(CreateTaskRequest task, String sqsQueueUrl) {
		try {
			log.info("Preparing to queue task assignment notification for task: {} to user: {}",
					task.getName(), task.getAssignedUserId());

			// Create notification payload
			Map<String, Object> notification = new HashMap<>();
			notification.put("type", "TASK_ASSIGNMENT");
			notification.put("taskName", task.getName());
			notification.put("assignedUserId", task.getAssignedUserId());
			notification.put("deadline", task.getDeadline().toString());
			notification.put("description", task.getDescription());

			// Serialize notification payload
			String messageBody = objectMapper.writeValueAsString(notification);
			log.debug("Serialized notification message body: {}", messageBody);

			// Queue to SQS for backup/retry purposes
			sendToSQS(sqsQueueUrl, messageBody, task.getAssignedUserId());

			// Send targeted SNS notification
			String topicArn = System.getenv("ASSIGNMENT_TOPIC_ARN");
			if (topicArn != null && !topicArn.trim().isEmpty()) {
				try {
					// Add message attributes for filtering
					Map<String, software.amazon.awssdk.services.sns.model.MessageAttributeValue> messageAttributes = new HashMap<>();

					// Add assigned user attribute
					messageAttributes.put("assignedUserId", software.amazon.awssdk.services.sns.model.MessageAttributeValue.builder()
							.dataType("String")
							.stringValue(task.getAssignedUserId())
							.build());

					// Add message type attribute
					messageAttributes.put("notificationType", software.amazon.awssdk.services.sns.model.MessageAttributeValue.builder()
							.dataType("String")
							.stringValue("TASK_ASSIGNMENT")
							.build());

					// Add messageType attribute for admin filtering
					messageAttributes.put("messageType", software.amazon.awssdk.services.sns.model.MessageAttributeValue.builder()
							.dataType("String")
							.stringValue("ALL")  // This will match the admin's filter policy
							.build());

					PublishRequest publishRequest = PublishRequest.builder()
							.topicArn(topicArn)
							.message(messageBody)
							.messageAttributes(messageAttributes)
							.build();

					snsClient.publish(publishRequest);
					log.info("Successfully published task assignment notification to SNS topic");
				} catch (SnsException e) {
					log.error("Failed to publish to SNS topic: {}", e.getMessage());
				}
			}

		} catch (Exception e) {
			log.error("Unexpected error in notification service: {}", e.getMessage());
			throw new RuntimeException("Failed to process notification", e);
		}
	}
	private void sendToSQS(String sqsQueueUrl, String messageBody, String assignedUserId) {
		int maxRetries = 3;
		int retryCount = 0;
		boolean messageSent = false;

		while (!messageSent && retryCount < maxRetries) {
			try {
				Map<String, MessageAttributeValue> messageAttributes = new HashMap<>();
				messageAttributes.put("assignedUserId", MessageAttributeValue.builder()
						.dataType("String")
						.stringValue(assignedUserId)
						.build());

				SendMessageRequest sendMessageRequest = SendMessageRequest.builder()
						.queueUrl(sqsQueueUrl)
						.messageBody(messageBody)
						.messageAttributes(messageAttributes)
						.build();

				log.debug("Attempting to send message to SQS, attempt {}/{}", retryCount + 1, maxRetries);
				sqsClient.sendMessage(sendMessageRequest);
				messageSent = true;
				log.info("Task assignment notification successfully queued for user: {}", assignedUserId);

			} catch (SdkException e) {
				retryCount++;
				if (retryCount == maxRetries) {
					log.error("Failed all {} attempts to send notification", maxRetries);
					throw e;
				}
				log.warn("Failed to send message, attempt {}/{}. Retrying...", retryCount, maxRetries);
				try {
					Thread.sleep(1000 * retryCount); // Exponential backoff
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
					throw new RuntimeException("Interrupted during retry delay", ie);
				}
			}
		}
	}

	public void notifyAdminOfStatusChange(Task task, String status, String snsTopicArn) {
		try {
			log.info("Notifying admin of status change for task: {}, status: {}", task.getId(), status);
			Map<String, software.amazon.awssdk.services.sns.model.MessageAttributeValue> messageAttributes = new HashMap<>();

			messageAttributes.put("notificationType", software.amazon.awssdk.services.sns.model.MessageAttributeValue.builder()
					.dataType("String")
					.stringValue("STATUS_CHANGE")
					.build());

			messageAttributes.put("messageType", software.amazon.awssdk.services.sns.model.MessageAttributeValue.builder()
					.dataType("String")
					.stringValue("ALL")
					.build());

			String notificationMessage = String.format(
					"Task Status Change Notification\n" +
							"Task ID: %s\n" +
							"Task Name: %s\n" +
							"Description: %s\n" +
							"Previous Status: %s\n" +
							"New Status: %s\n" +
							"Assigned User: %s\n" +
							"Deadline: %s",
					task.getId(),
					task.getName(),
					task.getDescription(),
					task.getStatus(),
					status,
					task.getAssignedUserId(),
					task.getDeadline());

			PublishRequest publishRequest = PublishRequest.builder()
					.topicArn(snsTopicArn)
					.message(notificationMessage)
					.messageAttributes(messageAttributes)
					.build();

			snsClient.publish(publishRequest);
			log.info("Successfully published status change notification for task: {}", task.getId());
		} catch (Exception e) {
			log.error("Failed to notify of status change for task {}: {}, error: {}", task.getId(), status, e.getMessage(), e);
			throw new RuntimeException("Failed to send status change notification", e);
		}
	}



	public void notifyNewAssignee(Task task, String newAssignee, String snsTopicArn) {
		try {
			log.info("Starting notification process for new assignee: {} and task: {}", newAssignee, task.getId());

			log.debug("Creating message attributes for notification.");
			Map<String, software.amazon.awssdk.services.sns.model.MessageAttributeValue> messageAttributes = new HashMap<>();

			messageAttributes.put("notificationType", software.amazon.awssdk.services.sns.model.MessageAttributeValue.builder()
					.dataType("String")
					.stringValue("TASK_REASSIGNMENT")
					.build());

			messageAttributes.put("assignedUserId", software.amazon.awssdk.services.sns.model.MessageAttributeValue.builder()
					.dataType("String")
					.stringValue(newAssignee)
					.build());

			messageAttributes.put("messageType", software.amazon.awssdk.services.sns.model.MessageAttributeValue.builder()
					.dataType("String")
					.stringValue("ALL")
					.build());

			String notificationMessage = String.format(
					"Task Reassignment Notification\n" +
							"Hello %s,\n\n" +
							"You have been assigned to the following task:\n" +
							"Task ID: %s\n" +
							"Task Name: %s\n" +
							"Description: %s\n" +
							"Current Status: %s\n" +
							"Deadline: %s\n" +
							"Previous Assignee: %s",
					newAssignee,
					task.getId(),
					task.getName(),
					task.getDescription(),
					task.getStatus(),
					task.getDeadline(),
					task.getAssignedUserId());

			PublishRequest publishRequest = PublishRequest.builder()
					.topicArn(snsTopicArn)
					.message(notificationMessage)
					.messageAttributes(messageAttributes)
					.build();

			snsClient.publish(publishRequest);
			log.info("Notification successfully sent for task reassignment. Task: {}, New Assignee: {}", task.getId(), newAssignee);
		} catch (Exception e) {
			log.error("Unexpected error occurred while notifying new assignee. snsTopicArn: {}, newAssignee: {}, taskId: {}, error: {}",
					snsTopicArn, newAssignee, task.getId(), e.getMessage(), e);
			throw new RuntimeException("Failed to send assignee notification", e);
		}
	}


	public void sendDeadlineNotification(Task task, String snsTopicArn) {
		try {
			log.info("Preparing deadline notification for task: {}, assigned to: {}", task.getId(), task.getAssignedUserId());

			// Create message attributes
			Map<String, software.amazon.awssdk.services.sns.model.MessageAttributeValue> messageAttributes = new HashMap<>();

			// Add assigned user attribute for regular user filtering
			messageAttributes.put("assignedUserId", software.amazon.awssdk.services.sns.model.MessageAttributeValue.builder()
					.dataType("String")
					.stringValue(task.getAssignedUserId())
					.build());

			// Add notification type
			messageAttributes.put("notificationType", software.amazon.awssdk.services.sns.model.MessageAttributeValue.builder()
					.dataType("String")
					.stringValue("DEADLINE_NOTIFICATION")
					.build());

			// Add messageType for admin filtering
			messageAttributes.put("messageType", software.amazon.awssdk.services.sns.model.MessageAttributeValue.builder()
					.dataType("String")
					.stringValue("ALL")
					.build());

			// Create notification message
			String message = String.format(
					"URGENT: Task Deadline Approaching\n" +
							"Task: %s\n" +
							"Description: %s\n" +
							"Deadline: %s\n" +
							"Current Status: %s\n" +
							"Time Remaining: Less than 1 hour",
					task.getName(),
					task.getDescription(),
					task.getDeadline(),
					task.getStatus());

			// Send notification
			PublishRequest publishRequest = PublishRequest.builder()
					.topicArn(snsTopicArn)
					.message(message)
					.messageAttributes(messageAttributes)
					.build();

			snsClient.publish(publishRequest);
			log.info("Successfully sent deadline notification for task: {}", task.getId());

		} catch (SnsException e) {
			log.error("Failed to send deadline notification for task: {}, error: {}", task.getId(), e.getMessage());
			throw new RuntimeException("Failed to send deadline notification", e);
		}
	}



	public void sendToExpiredTasksQueue(String messageBody, String queueUrl) {
		SendMessageRequest sendMessageRequest = SendMessageRequest.builder()
				.queueUrl(queueUrl)
				.messageBody(messageBody)
				.build();

		sqsClient.sendMessage(sendMessageRequest);
	}
}

