
package com.roczyno.aws.task_manager.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.roczyno.aws.task_manager.model.CreateTaskRequest;
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
	private final SesClient sesClient;
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

	public void notifyAdminOfStatusChange(String taskId, String status, String snsTopicArn) {
		try {
			log.info("Notifying admin of status change for task: {}, status: {}", taskId, status);
			Map<String, software.amazon.awssdk.services.sns.model.MessageAttributeValue> messageAttributes = new HashMap<>();

			// Add notification type attribute
			messageAttributes.put("notificationType", software.amazon.awssdk.services.sns.model.MessageAttributeValue.builder()
					.dataType("String")
					.stringValue("STATUS_CHANGE")
					.build());

			// Add messageType attribute for admin filtering
			messageAttributes.put("messageType", software.amazon.awssdk.services.sns.model.MessageAttributeValue.builder()
					.dataType("String")
					.stringValue("ALL")
					.build());

			String notificationMessage = String.format("Task %s status updated to %s", taskId, status);
			PublishRequest publishRequest = PublishRequest.builder()
					.topicArn(snsTopicArn)
					.message(notificationMessage)
					.messageAttributes(messageAttributes)
					.build();

			snsClient.publish(publishRequest);
			log.info("Successfully published status change notification for task: {}", taskId);
		} catch (Exception e) {
			log.error("Failed to notify of status change for task {}: {}, error: {}", taskId, status, e.getMessage(), e);
			throw new RuntimeException("Failed to send status change notification", e);
		}
	}

	public void notifyNewAssignee(String snsTopicArn, String newAssignee, String taskId) {
		try {
			log.info("Starting notification process for new assignee: {} and task: {}", newAssignee, taskId);

			log.debug("Creating message attributes for notification.");
			Map<String, software.amazon.awssdk.services.sns.model.MessageAttributeValue> messageAttributes = new HashMap<>();

			// Add notification type attribute
			messageAttributes.put("notificationType", software.amazon.awssdk.services.sns.model.MessageAttributeValue.builder()
					.dataType("String")
					.stringValue("TASK_REASSIGNMENT")
					.build());

			// Add assignedUserId for regular user filtering
			messageAttributes.put("assignedUserId", software.amazon.awssdk.services.sns.model.MessageAttributeValue.builder()
					.dataType("String")
					.stringValue(newAssignee)
					.build());

			// Add messageType attribute for admin filtering
			messageAttributes.put("messageType", software.amazon.awssdk.services.sns.model.MessageAttributeValue.builder()
					.dataType("String")
					.stringValue("ALL")
					.build());

			log.info("Preparing notification message.");
			String notificationMessage = String.format("User %s, you have been assigned a new task: %s", newAssignee, taskId);
			log.debug("Notification message: {}", notificationMessage);

			log.info("Building PublishRequest for SNS.");
			PublishRequest publishRequest = PublishRequest.builder()
					.topicArn(snsTopicArn)
					.message(notificationMessage)
					.messageAttributes(messageAttributes)
					.build();
			log.debug("PublishRequest built successfully with topicArn: {} and message: {}", snsTopicArn, notificationMessage);

			log.info("Publishing message to SNS topic: {}", snsTopicArn);
			snsClient.publish(publishRequest);
			log.info("Notification successfully sent for task reassignment. Task: {}, New Assignee: {}", taskId, newAssignee);
		} catch (IllegalArgumentException e) {
			log.error("Invalid argument provided. snsTopicArn: {}, newAssignee: {}, taskId: {}, error: {}",
					snsTopicArn, newAssignee, taskId, e.getMessage(), e);
			throw new RuntimeException("Invalid argument provided for notification process", e);
		} catch (NullPointerException e) {
			log.error("Null value encountered during notification process. Check snsClient, snsTopicArn, newAssignee, and taskId. Error: {}",
					e.getMessage(), e);
			throw new RuntimeException("Null value encountered during notification process", e);
		} catch (Exception e) {
			log.error("Unexpected error occurred while notifying new assignee. snsTopicArn: {}, newAssignee: {}, taskId: {}, error: {}",
					snsTopicArn, newAssignee, taskId, e.getMessage(), e);
			throw new RuntimeException("Failed to send assignee notification", e);
		}
	}


	public void sendDeadlineNotification(String taskId, String assignedUserId, LocalDateTime deadline, String snsTopicArn) {
		try {
			log.info("Preparing deadline notification for task: {}, assigned to: {}", taskId, assignedUserId);

			// Create message attributes
			Map<String, software.amazon.awssdk.services.sns.model.MessageAttributeValue> messageAttributes = new HashMap<>();

			// Add assigned user attribute for regular user filtering
			messageAttributes.put("assignedUserId", software.amazon.awssdk.services.sns.model.MessageAttributeValue.builder()
					.dataType("String")
					.stringValue(assignedUserId)
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
			String message = String.format("DEADLINE ALERT: Task %s assigned to %s is due on %s",
					taskId, assignedUserId, deadline.toString());

			// Send notification
			PublishRequest publishRequest = PublishRequest.builder()
					.topicArn(snsTopicArn)
					.message(message)
					.messageAttributes(messageAttributes)
					.build();

			snsClient.publish(publishRequest);
			log.info("Successfully sent deadline notification for task: {}", taskId);

		} catch (SnsException e) {
			log.error("Failed to send deadline notification for task: {}, error: {}", taskId, e.getMessage());
			throw new RuntimeException("Failed to send deadline notification", e);
		}
	}

	// Overloaded method to maintain backward compatibility if needed
	public void sendDeadlineNotification(String message, Map<String, software.amazon.awssdk.services.sns.model.MessageAttributeValue> messageAttributes, String snsTopicArn) {
		try {
			// Add messageType for admin filtering if not present
			if (!messageAttributes.containsKey("messageType")) {
				messageAttributes.put("messageType", software.amazon.awssdk.services.sns.model.MessageAttributeValue.builder()
						.dataType("String")
						.stringValue("ALL")
						.build());
			}

			PublishRequest publishRequest = PublishRequest.builder()
					.topicArn(snsTopicArn)
					.message(message)
					.messageAttributes(messageAttributes)
					.build();

			snsClient.publish(publishRequest);
			log.info("Successfully sent deadline notification");
		} catch (SnsException e) {
			log.error("Failed to send deadline notification, error: {}", e.getMessage());
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

