package com.roczyno.aws.task_manager.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.roczyno.aws.task_manager.model.CreateTaskRequest;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.util.HashMap;
import java.util.Map;

@Slf4j
public class QueueService {
	private final SqsClient sqsClient;


	public QueueService(SqsClient sqsClient) {
		this.sqsClient = sqsClient;
	}


	public void queueTaskAssignmentNotification(CreateTaskRequest task, String sqsQueueUrl) {
		try {
			log.info("Queuing task assignment notification for task: {} to user: {}",
					task.getName(), task.getAssignedUserId());

			// Create a structured message body
			Map<String, String> messageData = new HashMap<>();
			messageData.put("taskName", task.getName());
			messageData.put("description", task.getDescription());
			messageData.put("assignedUserId", task.getAssignedUserId());
			messageData.put("assignedUserName", task.getAssignedUserName());
			messageData.put("deadline", task.getDeadline().toString());
			messageData.put("notificationType", "TASK_ASSIGNMENT");

			String messageBody = new ObjectMapper().writeValueAsString(messageData);


			sendToSQS(sqsQueueUrl, messageBody, task.getAssignedUserId());

		} catch (Exception e) {
			log.error("Failed to queue task assignment notification: {}", e.getMessage());
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

	public void sendToExpiredTasksQueue(String messageBody, String queueUrl) {


		try {
			SendMessageRequest sendMessageRequest = SendMessageRequest.builder()
					.queueUrl(queueUrl)
					.messageBody(messageBody)
					.delaySeconds(0)
					.build();

			sqsClient.sendMessage(sendMessageRequest);

		} catch (SdkException e) {

			throw e;
		} catch (Exception e) {
			throw new RuntimeException("Failed to send message to queue", e);
		}
	}


}
