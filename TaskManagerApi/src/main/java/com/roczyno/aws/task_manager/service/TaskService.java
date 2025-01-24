package com.roczyno.aws.task_manager.service;

import com.amazonaws.services.lambda.runtime.LambdaLogger;
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
	private LambdaLogger lambdaLogger;

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
			taskItem.put("assignedUserName", AttributeValue.builder().s(request.getAssignedUserName()).build());
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

			UpdateItemRequest updateRequest = UpdateItemRequest.builder()
					.tableName(tableName)
					.key(Map.of("id", AttributeValue.builder().s(taskId).build()))
					.updateExpression("SET #status = :newStatus, #comment = :newComment, #updateTime = :updateTime, #completedAt = :completedAt")
					.conditionExpression("attribute_exists(id) AND (#status <> :newStatus)")
					.expressionAttributeNames(Map.of(
							"#status", "status",
							"#comment", "userComment",
							"#updateTime", "lastUpdatedAt",
							"#completedAt", "completedAt"
					))
					.expressionAttributeValues(Map.of(
							":newStatus", AttributeValue.builder().s(status.toString()).build(),
							":newComment", AttributeValue.builder().s(userComment).build(),
							":updateTime", AttributeValue.builder().s(LocalDateTime.now().toString()).build(),
							":completedAt", AttributeValue.builder().s(LocalDateTime.now().toString()).build()
					))
					.returnValues(ReturnValue.ALL_NEW)
					.build();

			// Perform the update
			UpdateItemResponse response = dynamoDbClient.updateItem(updateRequest);

			// If update successful, send notification with task details
			if (response != null && response.attributes() != null) {
				Task task = mapToTask(response.attributes());
				notificationService.notifyAdminOfStatusChange(task, status.toString(), snsTopicArn);
				logger.info("Notification sent for task status update: {}", taskId);
			}

		} catch (Exception e) {
			logger.error("Error updating task {} status: {}", taskId, e.getMessage());
			throw new RuntimeException("Task status update failed", e);
		}
	}

	public void reassignTask(String taskId, String newAssignee,String newAssigneeUserName, String tableName, String snsTopicArn) {
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

			// First, get the task to check if it exists and verify current status
			logger.info("Fetching task details before reassignment");
			GetItemResponse currentTaskResponse = dynamoDbClient.getItem(GetItemRequest.builder()
					.tableName(tableName)
					.key(Map.of("id", AttributeValue.builder().s(taskId).build()))
					.build());

			if (currentTaskResponse.item() == null || currentTaskResponse.item().isEmpty()) {
				logger.error("Task not found with ID: {}", taskId);
				throw new RuntimeException("Task not found");
			}

			// Store the current task state
			Task currentTask = mapToTask(currentTaskResponse.item());

			// Perform update
			try {
				logger.info("Updating task assignment in DynamoDB");
				UpdateItemResponse updateResponse = dynamoDbClient.updateItem(UpdateItemRequest.builder()
						.tableName(tableName)
						.key(Map.of("id", AttributeValue.builder().s(taskId).build()))
						.updateExpression("SET assignedUserId = :newAssignee, assignedUserName = :newAssigneeUserName")
						.conditionExpression("attribute_exists(id)")
						.expressionAttributeValues(Map.of(
								":newAssignee", AttributeValue.builder().s(newAssignee).build(),
								":newAssigneeUserName", AttributeValue.builder().s(newAssigneeUserName).build()
						))
						.returnValues(ReturnValue.ALL_NEW)
						.build());

				if (updateResponse.attributes() != null) {
					Task updatedTask = mapToTask(updateResponse.attributes());
					logger.info("Task successfully reassigned from {} to {}",
							currentTask.getAssignedUserId(), newAssignee);

					// Send notification with full task details
					try {
						logger.info("Sending notification to new assignee: {}", newAssignee);
						notificationService.notifyNewAssignee(updatedTask, newAssignee,newAssigneeUserName, snsTopicArn);
						logger.info("Notification sent successfully");
					} catch (Exception e) {
						logger.error("Failed to send notification to new assignee");
						logger.warn("Task was reassigned but notification failed: {}", e.getMessage());
					}
				}
			} catch (Exception e) {
				logger.error("Failed to update task in DynamoDB: {}", e.getMessage());
				throw new RuntimeException("Failed to update task", e);
			}

		} catch (Exception e) {
			logger.error("Task reassignment failed: {}", e.getMessage());
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

	public void reopenTask(String taskId, String tableName, String snsTopicArn) {
		logger.info("Starting task reopening process for task ID: {}", taskId);

		try {
			// Validate input parameters
			if (taskId == null || taskId.trim().isEmpty()) {
				logger.error("Invalid task ID provided");
				throw new IllegalArgumentException("Task ID cannot be null or empty");
			}

			// First, get the task to check if it exists and verify current status
			logger.info("Fetching task details before reopening");
			GetItemResponse taskResponse = dynamoDbClient.getItem(GetItemRequest.builder()
					.tableName(tableName)
					.key(Map.of("id", AttributeValue.builder().s(taskId).build()))
					.build());

			if (taskResponse.item() == null || taskResponse.item().isEmpty()) {
				logger.error("Task not found with ID: {}", taskId);
				throw new RuntimeException("Task not found");
			}

			String currentStatus = taskResponse.item().get("status").s();
			if (Status.OPEN.toString().equals(currentStatus)) {
				logger.warn("Task {} is already in OPEN status", taskId);
				return;
			}

			// Update the task status to OPEN
			logger.info("Updating task status to OPEN");
			UpdateItemRequest updateRequest = UpdateItemRequest.builder()
					.tableName(tableName)
					.key(Map.of("id", AttributeValue.builder().s(taskId).build()))
					.updateExpression("SET #status = :newStatus, #reopenTime = :reopenTime")
					.conditionExpression("attribute_exists(id)")
					.expressionAttributeNames(Map.of(
							"#status", "status",
							"#reopenTime", "lastUpdatedAt"
					))
					.expressionAttributeValues(Map.of(
							":newStatus", AttributeValue.builder().s(Status.OPEN.toString()).build(),
							":reopenTime", AttributeValue.builder().s(LocalDateTime.now().toString()).build()
					))
					.returnValues(ReturnValue.ALL_NEW)
					.build();

			UpdateItemResponse updateResponse = dynamoDbClient.updateItem(updateRequest);

			if (updateResponse.attributes() != null && !updateResponse.attributes().isEmpty()) {
				logger.info("Task successfully reopened: {}", taskId);

				// Map the updated task and send notification
				Task updatedTask = mapToTask(updateResponse.attributes());
				try {
					logger.info("Sending reopening notification");
					notificationService.notifyAdminOfStatusChange(updatedTask, "REOPENED", snsTopicArn);
					logger.info("Reopening notification sent successfully");
				} catch (Exception e) {
					logger.warn("Failed to send reopening notification, but task was reopened successfully: {}", e.getMessage());
				}
			}

		} catch (Exception e) {
			logger.error("Unexpected error during task reopening: {}", e.getMessage(), e);
			throw new RuntimeException("Task reopening failed due to an unexpected error", e);
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

			// Map the task for notification before deletion
			Task task = mapToTask(taskResponse.item());

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
					notificationService.notifyAdminOfStatusChange(task, "DELETED", snsTopicArn);
					logger.info("Deletion notification sent successfully");
				} catch (Exception e) {
					logger.warn("Failed to send deletion notification, but task was deleted successfully: {}", e.getMessage());
				}
			}

		} catch (Exception e) {
			logger.error("Unexpected error during task deletion: {}", e.getMessage(), e);
			throw new RuntimeException("Task deletion failed due to an unexpected error", e);
		}
	}




	public void setLogger(LambdaLogger lambdaLogger) {
		this.lambdaLogger = lambdaLogger;
	}

	private void sendDeadlineNotification(Task task, String snsTopicArn) {
		lambdaLogger.log(String.format("Preparing deadline notification for task: %s", task.getId()));

		try {
			notificationService.sendDeadlineNotification(task, snsTopicArn);
			lambdaLogger.log(String.format("Successfully sent deadline notification for task: %s", task.getId()));
		} catch (Exception e) {
			lambdaLogger.log(String.format("Failed to send deadline notification for task %s: %s",
					task.getId(), e.getMessage()));
			throw e;
		}
	}

	private void startExpirationWorkflow(Task task, String stepFunctionArn) {
		try {
			lambdaLogger.log(String.format("Starting expiration workflow for task %s with deadline %s",
					task.getId(), task.getDeadline()));

			ExpiredTaskInput input = ExpiredTaskInput.builder()
					.taskId(task.getId())
					.taskName(task.getName())
					.assignedUserId(task.getAssignedUserId())
					.deadline(String.valueOf(task.getDeadline()))
					.snsTopicArn(System.getenv("CLOSED_TOPIC_ARN"))
					.build();

			lambdaLogger.log(String.format("Current SNS Topic ARN value for step function: %s", System.getenv("CLOSED_TOPIC_ARN")));
			String jsonInput = objectMapper.writeValueAsString(input);
			lambdaLogger.log(String.format("Step Function input for task %s: %s", task.getId(), jsonInput));

			StartExecutionRequest executionRequest = StartExecutionRequest.builder()
					.stateMachineArn(stepFunctionArn)
					.input(jsonInput)
					.name(String.format("ExpiredTask-%s-%d", task.getId(), System.currentTimeMillis()))
					.build();

			StartExecutionResponse response = sfnClient.startExecution(executionRequest);
			lambdaLogger.log(String.format("Started expiration workflow for task %s with execution ARN: %s",
					task.getId(), response.executionArn()));


		} catch (Exception e) {
			lambdaLogger.log(String.format("Failed to start expiration workflow for task %s: %s",
					task.getId(), e.getMessage()));
			throw new RuntimeException("Failed to process expired task", e);
		}
	}
	private Task mapToTask(Map<String, AttributeValue> item) {
		return Task.builder()
				.id(item.get("id").s())
				.name(item.get("name").s())
				.description(item.getOrDefault("description",
						AttributeValue.builder().s("").build()).s())
				.assignedUserId(item.get("assignedUserId").s())
				.assignedUserName(item.get("assignedUserName").s())
				.deadline(LocalDateTime.parse(item.get("deadline").s()))
				.status(Status.valueOf(item.get("status").s()))
				.userComment(item.getOrDefault("userComment",
						AttributeValue.builder().s("").build()).s())
				.completedAt(item.get("completedAt") != null &&
						!item.get("completedAt").s().isEmpty() ?
						LocalDateTime.parse(item.get("completedAt").s()) :
						null)
				.build();
	}



	public void notifyApproachingDeadlines(String tableName, String snsTopicArn) {
		String methodId = UUID.randomUUID().toString();
		LocalDateTime now = LocalDateTime.now();
		LocalDateTime oneHourFromNow = now.plusHours(1);

		lambdaLogger.log(String.format("[MethodID: %s] Starting deadline check at: %s", methodId, now));
		lambdaLogger.log(String.format("[MethodID: %s] Parameters - Table: %s, SNS Topic: %s",
				methodId, tableName, snsTopicArn));
		lambdaLogger.log(String.format("[MethodID: %s] Checking for tasks with deadlines between %s and %s",
				methodId, now, oneHourFromNow));

		try {
			DynamoDbTable<Task> taskTable = enhancedClient.table(tableName, TableSchema.fromBean(Task.class));
			logger.debug("[MethodID: {}] Successfully initialized DynamoDB table reference", methodId);
			ScanEnhancedRequest scanRequest = buildScanRequest(now, oneHourFromNow);
			lambdaLogger.log(String.format("[MethodID: %s] Scan request built with filter: %s",
					methodId, scanRequest.filterExpression().expression()));

			AtomicInteger approachingDeadlineCount = new AtomicInteger(0);
			AtomicInteger expiredCount = new AtomicInteger(0);
			AtomicInteger errorCount = new AtomicInteger(0);


			taskTable.scan(scanRequest)
					.items()
					.forEach(task -> processTask(task, snsTopicArn, methodId,
							approachingDeadlineCount, expiredCount, errorCount, now));

			lambdaLogger.log(String.format("[MethodID: %s] Deadline check completed. Statistics:\n" +
							"- Tasks approaching deadline: %d\n" +
							"- Expired tasks: %d\n" +
							"- Errors encountered: %d",
					methodId, approachingDeadlineCount.get(),
					expiredCount.get(), errorCount.get()));

		} catch (Exception e) {
			lambdaLogger.log(String.format("[MethodID: %s] Critical error during deadline check: %s",
					methodId, e.getMessage()));
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

		lambdaLogger.log(String.format("[MethodID: %s] Starting expired tasks processing at: %s", methodId, now));
		lambdaLogger.log(String.format("[MethodID: %s] Parameters - Table: %s, Step Function ARN: %s",
				methodId, tableName, stepFunctionArn));

		try {
			QueryRequest queryRequest = buildExpiredTasksQuery(tableName, now);
			lambdaLogger.log(String.format("[MethodID: %s] Query request built with condition: %s",
					methodId, queryRequest.keyConditionExpression()));

			QueryResponse response = dynamoDbClient.query(queryRequest);
			int totalTasks = response.count();
			lambdaLogger.log(String.format("[MethodID: %s] Found %d expired tasks to process", methodId, totalTasks));

			AtomicInteger processedCount = new AtomicInteger(0);
			AtomicInteger errorCount = new AtomicInteger(0);

			response.items().forEach(item -> {
				Task task = mapToTask(item);
				String taskId = task.getId();

				lambdaLogger.log(String.format("[MethodID: %s][TaskID: %s] Processing expired task - Name: %s, Deadline: %s",
						methodId, taskId, task.getName(), task.getDeadline()));

				try {
					long startTime = System.currentTimeMillis();
					startExpirationWorkflow(task, stepFunctionArn);
					processedCount.incrementAndGet();
					lambdaLogger.log(String.format("[MethodID: %s][TaskID: %s] Successfully processed task in %dms",
							methodId, taskId, System.currentTimeMillis() - startTime));
				} catch (Exception e) {
					errorCount.incrementAndGet();
					lambdaLogger.log(String.format("[MethodID: %s][TaskID: %s] Failed to process task: %s",
							methodId, taskId, e.getMessage()));
				}
			});

			lambdaLogger.log(String.format("[MethodID: %s] Expired tasks processing completed. Statistics:\n" +
							"- Total tasks: %d\n" +
							"- Successfully processed: %d\n" +
							"- Errors encountered: %d",
					methodId, totalTasks, processedCount.get(), errorCount.get()));

		} catch (Exception e) {
			lambdaLogger.log(String.format("[MethodID: %s] Critical error during expired tasks processing: %s",
					methodId, e.getMessage()));
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
							":status", AttributeValue.builder().s("OPEN").build(),
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


