package com.roczyno.aws.task_manager;

import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roczyno.aws.task_manager.model.CreateTaskRequest;
import com.roczyno.aws.task_manager.model.Status;
import com.roczyno.aws.task_manager.model.Task;
import com.roczyno.aws.task_manager.service.NotificationService;
import com.roczyno.aws.task_manager.service.QueueService;
import com.roczyno.aws.task_manager.service.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.sfn.SfnClient;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class TaskServiceTest {

	@Mock
	private DynamoDbClient dynamoDbClient;
	@Mock
	private NotificationService notificationService;
	@Mock
	private QueueService queueService;
	@Mock
	private SfnClient sfnClient;
	@Mock
	private LambdaLogger lambdaLogger;

	private ObjectMapper objectMapper;
	private TaskService taskService;
	private final String TEST_TABLE_NAME = "test-tasks";
	private final String TEST_QUEUE_URL = "test-queue-url";
	private final String TEST_SNS_TOPIC = "test-sns-topic";

	@BeforeEach
	void setUp() {
		objectMapper = new ObjectMapper();
		taskService = new TaskService(dynamoDbClient, notificationService, queueService, objectMapper, sfnClient);
		taskService.setLogger(lambdaLogger);
	}

	@Test
	void createTask_Success() {
		// Arrange
		CreateTaskRequest request = new CreateTaskRequest();
		request.setName("Test Task");
		request.setDescription("Test Description");
		request.setDeadline(LocalDateTime.now().plusDays(1));
		request.setAssignedUserId("user123");
		request.setAssignedUserName("Test User");

		when(dynamoDbClient.putItem(any(PutItemRequest.class)))
				.thenReturn(PutItemResponse.builder().build());

		// Act
		assertDoesNotThrow(() -> taskService.createTask(request, TEST_TABLE_NAME, TEST_QUEUE_URL));

		// Assert
		verify(dynamoDbClient).putItem(any(PutItemRequest.class));
		verify(queueService).queueTaskAssignmentNotification(eq(request), eq(TEST_QUEUE_URL));
	}

	@Test
	void createTask_DuplicateId_ThrowsException() {
		// Arrange
		CreateTaskRequest request = new CreateTaskRequest();
		request.setName("Test Task");
		request.setDescription("Test Description");
		request.setDeadline(LocalDateTime.now().plusDays(1));
		request.setAssignedUserId("user123");
		request.setAssignedUserName("Test User");

		when(dynamoDbClient.putItem(any(PutItemRequest.class)))
				.thenThrow(ConditionalCheckFailedException.builder().build());

		// Act & Assert
		assertThrows(RuntimeException.class,
				() -> taskService.createTask(request, TEST_TABLE_NAME, TEST_QUEUE_URL));
	}

	@Test
	void updateTaskStatus_Success() {
		// Arrange
		String taskId = UUID.randomUUID().toString();
		Status newStatus = Status.COMPLETED;
		String userComment = "Task completed successfully";

		Map<String, AttributeValue> returnedAttributes = createMockTaskAttributes(taskId);

		when(dynamoDbClient.updateItem(any(UpdateItemRequest.class)))
				.thenReturn(UpdateItemResponse.builder()
						.attributes(returnedAttributes)
						.build());

		// Act
		assertDoesNotThrow(() -> taskService.updateTaskStatus(taskId, newStatus, userComment,
				TEST_TABLE_NAME, TEST_SNS_TOPIC));

		// Assert
		verify(dynamoDbClient).updateItem(any(UpdateItemRequest.class));
		verify(notificationService).notifyAdminOfStatusChange(any(Task.class), eq(newStatus.toString()),
				eq(TEST_SNS_TOPIC));
	}

//	@Test
//	void reopenTask_Success() {
//		// Arrange
//		String taskId = UUID.randomUUID().toString();
//		Map<String, AttributeValue> existingTask = createMockTaskAttributes(taskId);
//
//		when(dynamoDbClient.getItem(any(GetItemRequest.class)))
//				.thenReturn(GetItemResponse.builder().item(existingTask).build());
//
//		when(dynamoDbClient.updateItem(any(UpdateItemRequest.class)))
//				.thenReturn(UpdateItemResponse.builder()
//						.attributes(existingTask)
//						.build());
//
//		// Act
//		assertDoesNotThrow(() -> taskService.reopenTask(taskId, TEST_TABLE_NAME, TEST_SNS_TOPIC));
//
//		// Assert
//		verify(dynamoDbClient).getItem(any(GetItemRequest.class));
//		verify(dynamoDbClient).updateItem(any(UpdateItemRequest.class));
//		verify(notificationService).notifyAdminOfStatusChange(any(Task.class), eq("REOPENED"),
//				eq(TEST_SNS_TOPIC));
//	}

	@Test
	void deleteTask_Success() {
		// Arrange
		String taskId = UUID.randomUUID().toString();
		Map<String, AttributeValue> existingTask = createMockTaskAttributes(taskId);

		when(dynamoDbClient.getItem(any(GetItemRequest.class)))
				.thenReturn(GetItemResponse.builder().item(existingTask).build());

		when(dynamoDbClient.deleteItem(any(DeleteItemRequest.class)))
				.thenReturn(DeleteItemResponse.builder()
						.attributes(existingTask)
						.build());

		// Act
		assertDoesNotThrow(() -> taskService.deleteTask(taskId, TEST_TABLE_NAME, TEST_SNS_TOPIC));

		// Assert
		verify(dynamoDbClient).getItem(any(GetItemRequest.class));
		verify(dynamoDbClient).deleteItem(any(DeleteItemRequest.class));
		verify(notificationService).notifyAdminOfStatusChange(any(Task.class), eq("DELETED"),
				eq(TEST_SNS_TOPIC));
	}

	private Map<String, AttributeValue> createMockTaskAttributes(String taskId) {
		Map<String, AttributeValue> attributes = new HashMap<>();
		attributes.put("id", AttributeValue.builder().s(taskId).build());
		attributes.put("name", AttributeValue.builder().s("Test Task").build());
		attributes.put("description", AttributeValue.builder().s("Test Description").build());
		attributes.put("status", AttributeValue.builder().s(Status.OPEN.toString()).build());
		attributes.put("deadline", AttributeValue.builder().s(LocalDateTime.now().plusDays(1).toString()).build());
		attributes.put("assignedUserId", AttributeValue.builder().s("user123").build());
		attributes.put("assignedUserName", AttributeValue.builder().s("Test User").build());
		attributes.put("userComment", AttributeValue.builder().s("").build());
		return attributes;
	}
}
