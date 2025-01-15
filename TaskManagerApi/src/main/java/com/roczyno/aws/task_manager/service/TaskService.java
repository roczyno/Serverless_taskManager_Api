package com.roczyno.aws.task_manager.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.roczyno.aws.task_manager.model.CreateTaskRequest;
import com.roczyno.aws.task_manager.model.ExpiredTaskInput;
import com.roczyno.aws.task_manager.model.Status;
import com.roczyno.aws.task_manager.model.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.Page;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.ScanEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.StartExecutionRequest;
import software.amazon.awssdk.services.sfn.model.StartExecutionResponse;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public class TaskService {
	private final DynamoDbClient dynamoDbClient;
	private final DynamoDbEnhancedClient enhancedClient;
	private final NotificationService notificationService;
	private final ObjectMapper objectMapper;
	private final SfnClient sfnClient;


	private static final Logger logger = LoggerFactory.getLogger(TaskService.class);

	public TaskService(DynamoDbClient dynamoDbClient, NotificationService notificationService, ObjectMapper objectMapper, SfnClient sfnClient) {
		this.dynamoDbClient = dynamoDbClient;
		this.enhancedClient = DynamoDbEnhancedClient.builder()
				.dynamoDbClient(dynamoDbClient)
				.build();
		this.notificationService = notificationService;
		this.objectMapper = objectMapper;
		this.sfnClient = sfnClient;
	}

	public void createTask(CreateTaskRequest request, String tableName, String sqsQueueUrl) {
		logger.info("Starting task creation process for request: {}", request);

		String taskId = UUID.randomUUID().toString();
		logger.info("Generated task ID: {}", taskId);

		try {
			logger.info("Preparing task item for DynamoDB...");
			Map<String, AttributeValue> taskItem = new HashMap<>();
			taskItem.put("id", AttributeValue.builder().s(taskId).build());
			taskItem.put("name", AttributeValue.builder().s(request.getName()).build());
			taskItem.put("description", AttributeValue.builder().s(request.getDescription()).build());
			taskItem.put("status", AttributeValue.builder().s(Status.OPEN.toString()).build());
			taskItem.put("deadline", AttributeValue.builder().s(request.getDeadline().toString()).build());
			taskItem.put("assignedUserId", AttributeValue.builder().s(request.getAssignedUserId()).build());
			taskItem.put("createdAt", AttributeValue.builder().s(LocalDateTime.now().toString()).build());
			taskItem.put("userComment", AttributeValue.builder().s("").build());
			logger.info("Task item prepared: {}", taskItem);

			logger.info("Inserting task into DynamoDB table: {}", tableName);
			dynamoDbClient.putItem(PutItemRequest.builder()
					.tableName(tableName)
					.item(taskItem)
					.conditionExpression("attribute_not_exists(id)")
					.build());
			logger.info("Task successfully inserted into DynamoDB with ID: {}", taskId);

			logger.info("Queueing task assignment notification...");
			notificationService.queueTaskAssignmentNotification(request, sqsQueueUrl);
			logger.info("Task assignment notification queued successfully.");

		} catch (ConditionalCheckFailedException e) {
			logger.error("Task creation failed. Task with ID {} already exists.", taskId, e);
			throw new RuntimeException("Task creation failed - ID already exists", e);
		} catch (Exception e) {
			logger.error("Unexpected error occurred during task creation: {}", e.getMessage(), e);
			throw new RuntimeException("Task creation failed", e);
		}
	}

	public void updateTaskStatus(String taskId, Status status, String userComment, String tableName, String snsTopicArn) {
		logger.info("Updating task {} status to {}", taskId, status);
		try {
			// Log parameters for debugging
			logger.debug("Task ID: {}, Status: {}, User Comment: {}, Table Name: {}, SNS Topic ARN: {}",
					taskId, status, userComment, tableName, snsTopicArn);

			// Using expression-based updates consistently
			UpdateItemRequest updateRequest = UpdateItemRequest.builder()
					.tableName(tableName)
					.key(Map.of("id", AttributeValue.builder().s(taskId).build()))
					.updateExpression("SET #status = :newStatus, #comment = :newComment, #updateTime = :updateTime")
					.conditionExpression("attribute_exists(id) AND (#status <> :newStatus)")
					.expressionAttributeNames(Map.of(
							"#status", "status",
							"#comment", "userComment",
							"#updateTime", "lastUpdatedAt"
					))
					.expressionAttributeValues(Map.of(
							":newStatus", AttributeValue.builder().s(status.toString()).build(),
							":newComment", AttributeValue.builder().s(userComment).build(),
							":updateTime", AttributeValue.builder().s(LocalDateTime.now().toString()).build()
					))
					.returnValues(ReturnValue.ALL_NEW)
					.build();

			// Log the update request for debugging
			logger.debug("Update request: {}", updateRequest);

			// Perform the update
			UpdateItemResponse response = dynamoDbClient.updateItem(updateRequest);

			logger.info("Task status updated successfully: {}", taskId);

			// If update successful, send notification
			if (response != null) {
				notificationService.notifyAdminOfStatusChange(taskId, status.toString(), snsTopicArn);
				logger.info("Notification sent for task status update: {}", taskId);
			}

		} catch (ConditionalCheckFailedException e) {
			logger.error("Task {} not found or already in status {}: {}", taskId, status, e.getMessage());
			throw new RuntimeException("Task update failed - Task not found or invalid status transition", e);
		} catch (DynamoDbException e) {
			logger.error("DynamoDB error updating task {} status: {}", taskId, e.getMessage());
			throw new RuntimeException("DynamoDB error while updating task status", e);
		} catch (Exception e) {
			logger.error("Error updating task {} status: {}", taskId, e.getMessage());
			throw new RuntimeException("Task status update failed due to an unexpected error", e);
		}
	}

	public void reassignTask(String taskId, String newAssignee, String tableName, String snsTopicArn) {
		try {
			logger.info("Starting task reassignment process");
			logger.info("Parameters - Task ID: {}, New Assignee: {}, Table: {}", taskId, newAssignee, tableName);

			// Validate input parameters
			if (taskId == null || taskId.trim().isEmpty()) {
				throw new IllegalArgumentException("Task ID cannot be null or empty");
			}
			if (newAssignee == null || newAssignee.trim().isEmpty()) {
				throw new IllegalArgumentException("New assignee ID cannot be null or empty");
			}

			// Fetch current task
			logger.info("Fetching current task details for ID: {}", taskId);
			GetItemResponse currentTask = null;
			try {
				currentTask = dynamoDbClient.getItem(GetItemRequest.builder()
						.tableName(tableName)
						.key(Map.of("id", AttributeValue.builder().s(taskId).build()))
						.build());
			} catch (Exception e) {
				logger.error("Failed to fetch task from DynamoDB. Task ID: {}", taskId);
				logger.error("DynamoDB error details: {}", e.getMessage());
				throw new RuntimeException("Failed to fetch task details", e);
			}

			// Validate task exists
			if (currentTask.item() == null || currentTask.item().isEmpty()) {
				logger.error("Task not found in DynamoDB. Task ID: {}", taskId);
				throw new RuntimeException("Task not found");
			}

			// Log current state
			String currentAssignee = currentTask.item().get("assignedUserId").s();
			logger.info("Current task state - Task ID: {}, Current Assignee: {}, New Assignee: {}",
					taskId, currentAssignee, newAssignee);

			// Perform update
			try {
				logger.info("Updating task assignment in DynamoDB");
				dynamoDbClient.updateItem(UpdateItemRequest.builder()
						.tableName(tableName)
						.key(Map.of("id", AttributeValue.builder().s(taskId).build()))
						.updateExpression("SET assignedUserId = :newAssignee")
						.conditionExpression("attribute_exists(id)")
						.expressionAttributeValues(Map.of(
								":newAssignee", AttributeValue.builder().s(newAssignee).build()
						))
						.returnValues(ReturnValue.ALL_NEW)
						.build());
				logger.info("DynamoDB update successful");
			} catch (ConditionalCheckFailedException e) {
				logger.error("Task update failed - Task does not exist or condition check failed");
				logger.error("Error details: {}", e.getMessage());
				throw e;
			} catch (Exception e) {
				logger.error("Failed to update task in DynamoDB");
				logger.error("Error details: {}", e.getMessage());
				throw new RuntimeException("Failed to update task", e);
			}

			// Send notification
			try {
				logger.info("Sending notification to new assignee: {}", newAssignee);
				notificationService.notifyNewAssignee(snsTopicArn, newAssignee, taskId);
				logger.info("Notification sent successfully");
			} catch (Exception e) {
				logger.error("Failed to send notification to new assignee");
				logger.error("Notification error details: {}", e.getMessage());
				// Don't throw here - task is already reassigned
				logger.warn("Task was reassigned but notification failed");
			}

			logger.info("Task reassignment completed successfully");

		} catch (Exception e) {
			logger.error("Task reassignment failed");
			logger.error("Error type: {}", e.getClass().getName());
			logger.error("Error message: {}", e.getMessage());
			logger.error("Stack trace: ", e);
			throw e;
		}
	}



	public List<Task> getAllTasks(String tableName) {
		logger.info("Starting getAllTasks operation for table: {}", tableName);
		try {
			// Log table schema
			logger.info("Initializing table schema for Task class");
			TableSchema<Task> schema = TableSchema.fromBean(Task.class);

			logger.info("Creating DynamoDB table reference");
			DynamoDbTable<Task> taskTable = enhancedClient.table(tableName, schema);

			logger.info("Initiating table scan");
			List<Task> tasks = new ArrayList<>();

			// Add pagination logging
			ScanEnhancedRequest scanRequest = ScanEnhancedRequest.builder()
					.consistentRead(true)
					.build();

			try {
				int pageCount = 0;
				int totalItems = 0;

				Iterator<Page<Task>> results = taskTable.scan(scanRequest).iterator();
				while (results.hasNext()) {
					pageCount++;
					Page<Task> page = results.next();
					int pageSize = 0;

					for (Task task : page.items()) {
						tasks.add(task);
						pageSize++;
						totalItems++;
					}

					logger.info("Processed page {}: {} items", pageCount, pageSize);
				}

				logger.info("Scan completed. Total pages: {}, Total items: {}", pageCount, totalItems);

			} catch (Exception e) {
				logger.error("Error during scan operation: {}", e.getMessage());
				logger.error("Scan exception type: {}", e.getClass().getName());
				throw e;
			}

			if (tasks.isEmpty()) {
				logger.warn("No tasks found in table {}", tableName);
			} else {
				logger.info("Successfully retrieved {} tasks", tasks.size());
			}

			return tasks;

		} catch (DynamoDbException e) {
			logger.error("DynamoDB specific error: {}", e.getMessage());
			logger.error("Error Code: {}", e.awsErrorDetails().errorCode());
			logger.error("Service Name: {}", e.awsErrorDetails().serviceName());
			logger.error("Status Code: {}", e.statusCode());
			throw new RuntimeException("DynamoDB error while retrieving tasks: " + e.getMessage(), e);
		} catch (Exception e) {
			logger.error("Unexpected error retrieving tasks: {}", e.getMessage(), e);
			throw new RuntimeException("Failed to retrieve tasks: " + e.getMessage(), e);
		}
	}

	public List<Task> getTasksByAssignedUser(String userId, String tableName) {
		logger.info("Retrieving tasks for user: {}", userId);
		try {
			DynamoDbTable<Task> taskTable = enhancedClient.table(tableName, TableSchema.fromBean(Task.class));

			// Query using index and conditional key
			List<Task> tasks = new ArrayList<>();
			taskTable.index("AssignedUserIdIndex")
					.query(q -> q.queryConditional(
							QueryConditional.keyEqualTo(k -> k.partitionValue(userId)))
					)
					.stream()
					.flatMap(page -> page.items().stream())
					.forEach(tasks::add);

			logger.info("Successfully retrieved {} tasks for user {}", tasks.size(), userId);
			return tasks;
		} catch (Exception e) {
			logger.error("Error retrieving tasks for user {}: {}", userId, e.getMessage(), e);
			throw new RuntimeException("Failed to retrieve tasks for user", e);
		}
	}

	public void deleteTask(String taskId, String tableName, String snsTopicArn) {
		logger.info("Starting task deletion process for task ID: {}", taskId);

		try {
			// Validate input parameters
			if (taskId == null || taskId.trim().isEmpty()) {
				logger.error("Invalid task ID provided");
				throw new IllegalArgumentException("Task ID cannot be null or empty");
			}

			// First, get the task to check if it exists and to have details for notification
			logger.info("Fetching task details before deletion");
			GetItemResponse taskResponse = dynamoDbClient.getItem(GetItemRequest.builder()
					.tableName(tableName)
					.key(Map.of("id", AttributeValue.builder().s(taskId).build()))
					.build());

			if (taskResponse.item() == null || taskResponse.item().isEmpty()) {
				logger.error("Task not found with ID: {}", taskId);
				throw new RuntimeException("Task not found");
			}

			// Store relevant task details for notification
			String assignedUserId = taskResponse.item().get("assignedUserId").s();
			String taskName = taskResponse.item().get("name").s();

			// Delete the task
			logger.info("Deleting task from DynamoDB");
			DeleteItemRequest deleteRequest = DeleteItemRequest.builder()
					.tableName(tableName)
					.key(Map.of("id", AttributeValue.builder().s(taskId).build()))
					.conditionExpression("attribute_exists(id)")
					.returnValues(ReturnValue.ALL_OLD)
					.build();

			DeleteItemResponse deleteResponse = dynamoDbClient.deleteItem(deleteRequest);

			if (deleteResponse.attributes() != null && !deleteResponse.attributes().isEmpty()) {
				logger.info("Task successfully deleted: {}", taskId);

				// Send notification about task deletion
				try {
					logger.info("Sending deletion notification");
					Map<String, String> notificationDetails = Map.of(
							"taskId", taskId,
							"taskName", taskName,
							"assignedUserId", assignedUserId,
							"action", "DELETED",
							"timestamp", LocalDateTime.now().toString()
					);

					notificationService.notifyAdminOfStatusChange(taskId, "DELETED", snsTopicArn);
					logger.info("Deletion notification sent successfully");
				} catch (Exception e) {
					logger.warn("Failed to send deletion notification, but task was deleted successfully: {}", e.getMessage());
					// Don't throw here as the primary operation (deletion) was successful
				}
			}

		} catch (ConditionalCheckFailedException e) {
			logger.error("Task deletion failed - Task does not exist: {}", taskId);
			throw new RuntimeException("Task deletion failed - Task not found", e);
		} catch (DynamoDbException e) {
			logger.error("DynamoDB error during task deletion: {}", e.getMessage());
			logger.error("Error Code: {}", e.awsErrorDetails().errorCode());
			logger.error("Status Code: {}", e.statusCode());
			throw new RuntimeException("DynamoDB error while deleting task", e);
		} catch (Exception e) {
			logger.error("Unexpected error during task deletion: {}", e.getMessage(), e);
			throw new RuntimeException("Task deletion failed due to an unexpected error", e);
		}
	}


	private void sendDeadlineNotification(Task task, String snsTopicArn) {
		logger.info("Preparing deadline notification for task: {}", task.getId());

		Map<String, MessageAttributeValue> messageAttributes = new HashMap<>();
		messageAttributes.put("userId", MessageAttributeValue.builder()
				.dataType("String")
				.stringValue(task.getAssignedUserId())
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

		logger.debug("Notification message for task {}: {}", task.getId(), message);

		try {
			notificationService.sendDeadlineNotification(task.getId(),task.getAssignedUserId(),task.getDeadline(), snsTopicArn);
			logger.info("Successfully sent deadline notification for task: {}", task.getId());
		} catch (Exception e) {
			logger.error("Failed to send deadline notification for task {}: {}", task.getId(), e.getMessage());
			throw e;
		}
	}


	private void startExpirationWorkflow(Task task, String stepFunctionArn) {
		try {
			logger.info("Starting expiration workflow for task {} with deadline {}", task.getId(), task.getDeadline());

			ExpiredTaskInput input = ExpiredTaskInput.builder()
					.taskId(task.getId())
					.taskName(task.getName())
					.assignedUserId(task.getAssignedUserId())
					.deadline(task.getDeadline())
					.build();

			String jsonInput = objectMapper.writeValueAsString(input);
			logger.debug("Step Function input for task {}: {}", task.getId(), jsonInput);

			StartExecutionRequest executionRequest = StartExecutionRequest.builder()
					.stateMachineArn(stepFunctionArn)
					.input(jsonInput)
					.name(String.format("ExpiredTask-%s-%d", task.getId(), System.currentTimeMillis()))
					.build();

			StartExecutionResponse response = sfnClient.startExecution(executionRequest);
			logger.info("Started expiration workflow for task {} with execution ARN: {}",
					task.getId(), response.executionArn());

		} catch (Exception e) {
			logger.error("Failed to start expiration workflow for task {}: {}",
					task.getId(), e.getMessage(), e);
			throw new RuntimeException("Failed to process expired task", e);
		}
	}
	private Task mapToTask(Map<String, AttributeValue> item) {
		return Task.builder()
				.id(item.get("id").s())
				.name(item.get("name").s())
				.assignedUserId(item.get("assignedUserId").s())
				.deadline(LocalDateTime.parse(item.get("deadline").s()))
				.status(Status.valueOf(item.get("status").s()))
				.build();
	}



	public void notifyApproachingDeadlines(String tableName, String snsTopicArn) {
		String methodId = UUID.randomUUID().toString();
		LocalDateTime now = LocalDateTime.now();
		LocalDateTime oneHourFromNow = now.plusHours(1);

		logger.info("[MethodID: {}] Starting deadline check at: {}", methodId, now);
		logger.debug("[MethodID: {}] Parameters - Table: {}, SNS Topic: {}", methodId, tableName, snsTopicArn);
		logger.info("[MethodID: {}] Checking for tasks with deadlines between {} and {}",
				methodId, now, oneHourFromNow);

		try {
			DynamoDbTable<Task> taskTable = enhancedClient.table(tableName, TableSchema.fromBean(Task.class));
			logger.debug("[MethodID: {}] Successfully initialized DynamoDB table reference", methodId);

			// Build scan request with modified status check for "OPEN"
			ScanEnhancedRequest scanRequest = buildScanRequest(now, oneHourFromNow);
			logger.debug("[MethodID: {}] Scan request built with filter: {}",
					methodId, scanRequest.filterExpression().expression());

			AtomicInteger approachingDeadlineCount = new AtomicInteger(0);
			AtomicInteger expiredCount = new AtomicInteger(0);
			AtomicInteger errorCount = new AtomicInteger(0);

			taskTable.scan(scanRequest)
					.items()
					.forEach(task -> processTask(task, snsTopicArn, methodId,
							approachingDeadlineCount, expiredCount, errorCount, now));

			logger.info("[MethodID: {}] Deadline check completed. Statistics:\n" +
							"- Tasks approaching deadline: {}\n" +
							"- Expired tasks: {}\n" +
							"- Errors encountered: {}",
					methodId, approachingDeadlineCount.get(),
					expiredCount.get(), errorCount.get());

		} catch (Exception e) {
			logger.error("[MethodID: {}] Critical error during deadline check: {}",
					methodId, e.getMessage(), e);
			throw new RuntimeException("Deadline check failed", e);
		}
	}

	private ScanEnhancedRequest buildScanRequest(LocalDateTime now, LocalDateTime oneHourFromNow) {
		logger.debug("Building scan request for time range: {} to {}", now, oneHourFromNow);

		// Format dates to match your DynamoDB format
		DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
		String nowFormatted = now.format(formatter);
		String oneHourFromNowFormatted = oneHourFromNow.format(formatter);

		try {
			return ScanEnhancedRequest.builder()
					.filterExpression(Expression.builder()
							.expression("#taskStatus = :status " +
									"AND #taskDeadline BETWEEN :now AND :oneHour")
							.putExpressionName("#taskStatus", "status")
							.putExpressionName("#taskDeadline", "deadline")
							.putExpressionValue(":status", AttributeValue.builder().s("OPEN").build())
							.putExpressionValue(":now", AttributeValue.builder().s(nowFormatted).build())
							.putExpressionValue(":oneHour", AttributeValue.builder().s(oneHourFromNowFormatted).build())
							.build())
					.build();
		} catch (Exception e) {
			logger.error("Failed to build scan request: {}", e.getMessage(), e);
			throw new RuntimeException("Failed to build scan request", e);
		}
	}

	private void processTask(Task task, String snsTopicArn, String methodId,
							 AtomicInteger approachingCount, AtomicInteger expiredCount,
							 AtomicInteger errorCount, LocalDateTime now) {
		String taskId = task.getId();
		logger.info("[MethodID: {}][TaskID: {}] Processing task - Name: {}, Deadline: {}, Status: {}",
				methodId, taskId, task.getName(), task.getDeadline(), task.getStatus());

		try {
			LocalDateTime deadline = parseDeadline(String.valueOf(task.getDeadline()));

			if (isApproachingDeadline(now, deadline)) {
				approachingCount.incrementAndGet();
				sendDeadlineNotification(task, snsTopicArn);
				logger.info("[MethodID: {}][TaskID: {}] Notification sent for approaching deadline",
						methodId, taskId);
			}

			if (deadline.isBefore(now)) {
				expiredCount.incrementAndGet();
				handleExpiredTask(task, methodId, taskId);
			}
		} catch (Exception e) {
			errorCount.incrementAndGet();
			logger.error("[MethodID: {}][TaskID: {}] Failed to process task: {}",
					methodId, taskId, e.getMessage(), e);
		}
	}

	private LocalDateTime parseDeadline(String deadlineStr) {
		try {
			return LocalDateTime.parse(deadlineStr, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));
		} catch (DateTimeParseException e) {
			logger.error("Failed to parse deadline: {}", deadlineStr);
			throw new IllegalArgumentException("Invalid deadline format: " + deadlineStr);
		}
	}

	private boolean isApproachingDeadline(LocalDateTime now, LocalDateTime deadline) {
		Duration timeUntilDeadline = Duration.between(now, deadline);
		return !deadline.isBefore(now) && timeUntilDeadline.toHours() <= 1;
	}

	private void handleExpiredTask(Task task, String methodId, String taskId) {
		try {
			notificationService.sendToExpiredTasksQueue(
					String.valueOf(task),
					System.getenv("EXPIRED_TASKS_QUEUE_URL")
			);
			logger.info("[MethodID: {}][TaskID: {}] Successfully queued expired task", methodId, taskId);
		} catch (Exception e) {
			logger.error("[MethodID: {}][TaskID: {}] Failed to queue expired task: {}",
					methodId, taskId, e.getMessage(), e);
			throw e;
		}
	}
	public void processExpiredTasks(String tableName, String stepFunctionArn) {
		String methodId = UUID.randomUUID().toString();
		LocalDateTime now = LocalDateTime.now();

		logger.info("[MethodID: {}] Starting expired tasks processing at: {}", methodId, now);
		logger.debug("[MethodID: {}] Parameters - Table: {}, Step Function ARN: {}",
				methodId, tableName, stepFunctionArn);

		try {
			QueryRequest queryRequest = buildExpiredTasksQuery(tableName, now);
			logger.debug("[MethodID: {}] Query request built with condition: {}",
					methodId, queryRequest.keyConditionExpression());

			QueryResponse response = dynamoDbClient.query(queryRequest);
			int totalTasks = response.count();
			logger.info("[MethodID: {}] Found {} expired tasks to process", methodId, totalTasks);

			AtomicInteger processedCount = new AtomicInteger(0);
			AtomicInteger errorCount = new AtomicInteger(0);

			response.items().forEach(item -> {
				Task task = mapToTask(item);
				String taskId = task.getId();

				logger.info("[MethodID: {}][TaskID: {}] Processing expired task - Name: {}, Deadline: {}",
						methodId, taskId, task.getName(), task.getDeadline());

				try {
					long startTime = System.currentTimeMillis();
					startExpirationWorkflow(task, stepFunctionArn);
					processedCount.incrementAndGet();
					logger.info("[MethodID: {}][TaskID: {}] Successfully processed task in {}ms",
							methodId, taskId, System.currentTimeMillis() - startTime);
				} catch (Exception e) {
					errorCount.incrementAndGet();
					logger.error("[MethodID: {}][TaskID: {}] Failed to process task: {}",
							methodId, taskId, e.getMessage(), e);
				}
			});

			logger.info("[MethodID: {}] Expired tasks processing completed. Statistics:\n" +
							"- Total tasks: {}\n" +
							"- Successfully processed: {}\n" +
							"- Errors encountered: {}",
					methodId, totalTasks, processedCount.get(), errorCount.get());

		} catch (Exception e) {
			logger.error("[MethodID: {}] Critical error during expired tasks processing: {}",
					methodId, e.getMessage(), e);
			logger.error("[MethodID: {}] Stack trace: ", methodId, e);
			throw new RuntimeException("Expired tasks processing failed", e);
		}
	}
	private QueryRequest buildExpiredTasksQuery(String tableName, LocalDateTime now) {
		logger.debug("Building query request for expired tasks at time: {}", now);

		try {
			QueryRequest request = QueryRequest.builder()
					.tableName(tableName)
					.indexName("StatusDeadlineIndex")
					.keyConditionExpression("#taskStatus = :status AND #taskDeadline <= :now")
					.expressionAttributeNames(Map.of(
							"#taskStatus", "status",
							"#taskDeadline", "deadline"
					))
					.expressionAttributeValues(Map.of(
							":status", AttributeValue.builder().s("ACTIVE").build(),
							":now", AttributeValue.builder().s(now.toString()).build()
					))
					.build();

			logger.debug("Successfully built query request with condition: {}",
					request.keyConditionExpression());
			return request;
		} catch (Exception e) {
			logger.error("Failed to build query request: {}", e.getMessage(), e);
			throw new RuntimeException("Failed to build query request", e);
		}
	}
}


