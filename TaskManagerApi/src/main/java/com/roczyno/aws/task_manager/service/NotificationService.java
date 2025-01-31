
package com.roczyno.aws.task_manager.service;

import com.roczyno.aws.task_manager.model.Task;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.SnsException;

import java.util.HashMap;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class NotificationService {

	private final SnsClient snsClient;



	public void notifyAdminOfStatusChange(Task task, String status, String snsTopicArn) {
		try {
			log.info("Notifying admin of status change for task: {}, status: {}", task.getId(), status);
			Map<String, MessageAttributeValue> messageAttributes = new HashMap<>();

			messageAttributes.put("notificationType", MessageAttributeValue.builder()
					.dataType("String")
					.stringValue("STATUS_CHANGE")
					.build());

			messageAttributes.put("messageType", MessageAttributeValue.builder()
					.dataType("String")
					.stringValue("ALL")
					.build());
			messageAttributes.put("assignedUserId", MessageAttributeValue.builder()
					.dataType("String")
					.stringValue(task.getAssignedUserId())
					.build());

			String notificationMessage = String.format(
					"Task Status Change Notification\n" +
							"Task ID: %s\n" +
							"Task Name: %s\n" +
							"Description: %s\n" +
							"Previous Status: %s\n" +
							"New Status: %s\n" +
							"Assigned User: %s\n" +
							"Assigned UserName: %s\n" +
							"Deadline: %s",
					task.getId(),
					task.getName(),
					task.getDescription(),
					task.getStatus(),
					status,
					task.getAssignedUserId(),
					task.getAssignedUserName(),
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



	public void notifyNewAssignee(Task task, String newAssignee,String newAssigneeUserName, String snsTopicArn) {
		try {
			log.info("Starting notification process for new assignee: {} and task: {}", newAssignee, task.getId());

			log.debug("Creating message attributes for notification.");
			Map<String, MessageAttributeValue> messageAttributes = new HashMap<>();

			messageAttributes.put("notificationType", MessageAttributeValue.builder()
					.dataType("String")
					.stringValue("TASK_REASSIGNMENT")
					.build());

			messageAttributes.put("assignedUserId", MessageAttributeValue.builder()
					.dataType("String")
					.stringValue(newAssignee)
					.build());

			messageAttributes.put("messageType", MessageAttributeValue.builder()
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
							"Previous AssigneeId: %s"+
							"Previous AssigneeUserName: %s",
					newAssigneeUserName,
					task.getId(),
					task.getName(),
					task.getDescription(),
					task.getStatus(),
					task.getDeadline(),
					task.getAssignedUserId(),
					task.getAssignedUserName());

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


			Map<String, MessageAttributeValue> messageAttributes = new HashMap<>();


			messageAttributes.put("assignedUserId", MessageAttributeValue.builder()
					.dataType("String")
					.stringValue(task.getAssignedUserId())
					.build());


			messageAttributes.put("notificationType", MessageAttributeValue.builder()
					.dataType("String")
					.stringValue("DEADLINE_NOTIFICATION")
					.build());


			messageAttributes.put("messageType", MessageAttributeValue.builder()
					.dataType("String")
					.stringValue("ALL")
					.build());
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





}

