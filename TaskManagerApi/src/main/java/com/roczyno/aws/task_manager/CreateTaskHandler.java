package com.roczyno.aws.task_manager;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.roczyno.aws.task_manager.config.AwsConfig;
import com.roczyno.aws.task_manager.model.CreateTaskRequest;
import com.roczyno.aws.task_manager.model.Status;
import com.roczyno.aws.task_manager.service.NotificationService;
import com.roczyno.aws.task_manager.service.TaskService;
import com.roczyno.aws.task_manager.util.AuthorizationUtil;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;

public class CreateTaskHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
	private final TaskService taskService;
	private final String tableName;
	private final String sqsQueueName;
	private static final Map<String, String> CORS_HEADERS = Map.of(
			"Content-Type", "application/json",
			"Access-Control-Allow-Origin", "*",
			"Access-Control-Allow-Methods", "POST",
			"Access-Control-Allow-Headers", "Content-Type,X-Amz-Date,Authorization,X-Api-Key,X-Amz-Security-Token"
	);

	public CreateTaskHandler() {
		NotificationService notificationService = new NotificationService(
				AwsConfig.sqsClient(),
				AwsConfig.objectMapper(),
				AwsConfig.snsClient()
		);
		this.taskService = new TaskService(AwsConfig.dynamoDbClient(), notificationService,AwsConfig.objectMapper(),AwsConfig.sfnClient());
		this.tableName = System.getenv("TASKS_TABLE_NAME");
		this.sqsQueueName = System.getenv("TASKS_QUEUE_URL");

		// Validate environment variables at initialization
		if (tableName == null || tableName.isEmpty()) {
			throw new IllegalStateException("TASKS_TABLE_NAME environment variable is not set");
		}
		if (sqsQueueName == null || sqsQueueName.isEmpty()) {
			throw new IllegalStateException("TASKS_QUEUE_URL environment variable is not set");
		}
	}

	@Override
	public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
		if (!AuthorizationUtil.isAdmin(input)) {
			return AuthorizationUtil.forbidden();
		}
		LambdaLogger logger = context.getLogger();
		APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent()
				.withHeaders(CORS_HEADERS);

		try {
			// Validate input body
			if (input.getBody() == null || input.getBody().isEmpty()) {
				logger.log("ERROR: Empty request body received");
				return response.withStatusCode(400)
						.withBody("{\"error\":\"Request body is required\"}");
			}

			// Parse and validate JSON
			JsonObject taskRequest;
			try {
				taskRequest = JsonParser.parseString(input.getBody()).getAsJsonObject();
			} catch (Exception e) {
				logger.log("ERROR: Invalid JSON format: " + e.getMessage());
				return response.withStatusCode(400)
						.withBody("{\"error\":\"Invalid JSON format\"}");
			}

			// Validate required fields with detailed error messages
			Map<String, String> validationErrors = new HashMap<>();

			if (!taskRequest.has("name") || taskRequest.get("name").getAsString().trim().isEmpty()) {
				validationErrors.put("name", "Task name is required and cannot be empty");
			}
			if (!taskRequest.has("assignedUserId") || taskRequest.get("assignedUserId").getAsString().trim().isEmpty()) {
				validationErrors.put("assignedUserId", "Assignee User ID is required and cannot be empty");
			}
			if (!taskRequest.has("deadline")) {
				validationErrors.put("deadline", "Deadline is required");
			}

			if (!validationErrors.isEmpty()) {
				String errorJson = new GsonBuilder().create().toJson(Map.of("errors", validationErrors));
				logger.log("Validation errors: " + errorJson);
				return response.withStatusCode(400).withBody(errorJson);
			}

			// Create task request object
			CreateTaskRequest createTaskRequest = new CreateTaskRequest();
			createTaskRequest.setName(taskRequest.get("name").getAsString().trim());
			createTaskRequest.setDescription(taskRequest.has("description") ?
					taskRequest.get("description").getAsString().trim() : "");
			createTaskRequest.setStatus(Status.OPEN);
			createTaskRequest.setAssignedUserId(taskRequest.get("assignedUserId").getAsString().trim());

			try {
				String deadline = taskRequest.get("deadline").getAsString();
				createTaskRequest.setDeadline(LocalDateTime.parse(deadline));
			} catch (DateTimeParseException e) {
				logger.log("ERROR: Invalid deadline format: " + e.getMessage());
				return response.withStatusCode(400)
						.withBody("{\"error\":\"Invalid deadline format. Expected format: yyyy-MM-ddTHH:mm:ss\"}");
			}

			// Create task with explicit error handling
			try {
				taskService.createTask(createTaskRequest, tableName, sqsQueueName);
			} catch (ConditionalCheckFailedException e) {
				logger.log("ERROR: Duplicate task ID: " + e.getMessage());
				return response.withStatusCode(409)
						.withBody("{\"error\":\"Task creation failed - duplicate ID\"}");
			} catch (ResourceNotFoundException e) {
				logger.log("ERROR: DynamoDB table not found: " + e.getMessage());
				return response.withStatusCode(404)
						.withBody("{\"error\":\"DynamoDB table not found\"}");
			} catch (Exception e) {
				logger.log("ERROR: Task creation failed: " + e.getMessage());
				throw e; // Let the outer catch handle unexpected errors
			}

			return response.withStatusCode(201)
					.withBody("{\"status\":\"success\",\"message\":\"Task created successfully\"}");

		} catch (AwsServiceException ex) {
			logger.log("AWS Service Error: " + ex.getMessage());
			logger.log("Error Details: " + ex.awsErrorDetails().errorMessage());
			return response.withStatusCode(502)
					.withBody(String.format("{\"error\":\"AWS Service Error\",\"details\":\"%s\"}",
							ex.awsErrorDetails().errorMessage()));
		} catch (Exception ex) {
			logger.log("Unexpected error: " + ex.getMessage());
			if (ex.getCause() != null) {
				logger.log("Caused by: " + ex.getCause().getMessage());
			}
			return response.withStatusCode(500)
					.withBody("{\"error\":\"Internal server error\",\"message\":\"An unexpected error occurred\"}");
		}
	}
}

