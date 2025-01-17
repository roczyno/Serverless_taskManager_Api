package com.roczyno.aws.task_manager;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.roczyno.aws.task_manager.config.AwsConfig;
import com.roczyno.aws.task_manager.model.Status;
import com.roczyno.aws.task_manager.service.NotificationService;
import com.roczyno.aws.task_manager.service.TaskService;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

import java.util.HashMap;
import java.util.Map;

import static com.google.common.base.Throwables.getStackTraceAsString;

public class UpdateTaskStatusHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
	private final TaskService taskService;
	private final String tableName;
	private final String snsTopicArn;
	private static final Map<String, String> CORS_HEADERS = Map.of(
			"Content-Type", "application/json",
			"Access-Control-Allow-Origin", "*",
			"Access-Control-Allow-Methods", "PUT, POST",
			"Access-Control-Allow-Headers", "Content-Type,X-Amz-Date,Authorization,X-Api-Key,X-Amz-Security-Token"
	);

	public UpdateTaskStatusHandler() {
		NotificationService notificationService = new NotificationService(
				AwsConfig.sesClient(),
				AwsConfig.sqsClient(),
				AwsConfig.objectMapper(),
				AwsConfig.snsClient()
		);
		this.taskService = new TaskService(AwsConfig.dynamoDbClient(), notificationService,AwsConfig.objectMapper(),AwsConfig.sfnClient());
		this.snsTopicArn = System.getenv("COMPLETE_TOPIC_ARN");
		this.tableName = System.getenv("TASKS_TABLE_NAME");

		// Validate environment variables
		validateEnvironmentVariables();
	}

	private void validateEnvironmentVariables() {
		if (tableName == null || tableName.isEmpty()) {
			throw new IllegalStateException("TASKS_TABLE_NAME environment variable is not set");
		}
		if (snsTopicArn == null || snsTopicArn.isEmpty()) {
			throw new IllegalStateException("ASSIGNMENT_TOPIC_ARN environment variable is not set");
		}
	}

	@Override
	public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
		LambdaLogger logger = context.getLogger();
		APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent()
				.withHeaders(CORS_HEADERS);

		try {
			// Log request details
			logger.log("Request received - Request ID: " + context.getAwsRequestId());
			logger.log("Remaining time in milliseconds: " + context.getRemainingTimeInMillis());

			String requestBody = input.getBody();
			if (requestBody == null || requestBody.isEmpty()) {
				logger.log("Error: Empty request body");
				return response
						.withStatusCode(400)
						.withBody(formatErrorResponse("Request body is empty", "ValidationError", null));
			}

			JsonObject taskRequest = JsonParser.parseString(requestBody).getAsJsonObject();
			logger.log("Parsed request body: " + taskRequest.toString());

			// Validate required fields
			validateRequestFields(taskRequest, logger);

			String taskId = taskRequest.get("taskId").getAsString();
			String statusString = taskRequest.get("status").getAsString().toUpperCase();
			String userComment = taskRequest.has("comment") ? taskRequest.get("comment").getAsString() : "";

			// Validate status enum
			Status status = validateAndParseStatus(statusString, logger);

			logger.log(String.format("Processing status update - Task ID: %s, New Status: %s, Comment: %s",
					taskId, status, userComment));

			// Update task status
			taskService.updateTaskStatus(taskId, status, userComment, tableName, snsTopicArn);

			logger.log("Task status update successful - Task ID: " + taskId);
			return response
					.withStatusCode(200)
					.withBody("{\"status\":\"success\",\"message\":\"Task status updated successfully\"}");

		} catch (ConditionalCheckFailedException ex) {
			logger.log("Conditional check failed: " + getStackTraceAsString(ex));
			return response
					.withStatusCode(409)
					.withBody(formatErrorResponse(
							"Task not found or invalid status transition",
							"ConditionalCheckFailedException",
							ex
					));
		} catch (AwsServiceException ex) {
			logger.log("AWS service error: " + getStackTraceAsString(ex));
			return response
					.withStatusCode(502)
					.withBody(formatErrorResponse(
							ex.awsErrorDetails().errorMessage(),
							ex.awsErrorDetails().errorCode(),
							ex
					));
		} catch (IllegalArgumentException ex) {
			logger.log("Validation error: " + getStackTraceAsString(ex));
			return response
					.withStatusCode(400)
					.withBody(formatErrorResponse(
							ex.getMessage(),
							"ValidationError",
							ex
					));
		} catch (Exception ex) {
			logger.log("Unexpected error: " + getStackTraceAsString(ex));
			return response
					.withStatusCode(500)
					.withBody(formatErrorResponse(
							"Internal server error",
							"UnexpectedError",
							ex
					));
		}
	}

	private void validateRequestFields(JsonObject taskRequest, LambdaLogger logger) {
		if (!taskRequest.has("taskId") || taskRequest.get("taskId").getAsString().isEmpty()) {
			logger.log("Validation failed: Missing taskId");
			throw new IllegalArgumentException("Task ID is required");
		}

		if (!taskRequest.has("status") || taskRequest.get("status").getAsString().isEmpty()) {
			logger.log("Validation failed: Missing status");
			throw new IllegalArgumentException("Status is required");
		}
	}

	private Status validateAndParseStatus(String statusString, LambdaLogger logger) {
		try {
			return Status.valueOf(statusString);
		} catch (IllegalArgumentException ex) {
			logger.log("Invalid status value: " + statusString);
			throw new IllegalArgumentException("Invalid status value: " + statusString +
					". Valid values are: " + String.join(", ",
					java.util.Arrays.stream(Status.values())
							.map(Status::name)
							.toArray(String[]::new)));
		}
	}

	private String formatErrorResponse(String message, String errorType, Exception ex) {
		Map<String, Object> errorDetails = new HashMap<>();
		errorDetails.put("error", message);
		errorDetails.put("type", errorType);

		if (ex != null) {
			errorDetails.put("details", ex.getMessage());
			errorDetails.put("stackTrace", getStackTraceAsString(ex));
		}

		return new GsonBuilder().create().toJson(errorDetails);
	}
}
