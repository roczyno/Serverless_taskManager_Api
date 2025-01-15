package com.roczyno.aws.task_manager;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.roczyno.aws.task_manager.config.AwsConfig;
import com.roczyno.aws.task_manager.service.NotificationService;
import com.roczyno.aws.task_manager.service.TaskService;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;

import java.util.Map;

public class DeleteTaskHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
	private final TaskService taskService;
	private final String tableName;
	private final String snsTopicArn;
	private static final Map<String, String> CORS_HEADERS = Map.of(
			"Content-Type", "application/json",
			"Access-Control-Allow-Origin", "*",
			"Access-Control-Allow-Methods", "DELETE",
			"Access-Control-Allow-Headers", "Content-Type,X-Amz-Date,Authorization,X-Api-Key,X-Amz-Security-Token"
	);

	public DeleteTaskHandler() {
		NotificationService notificationService = new NotificationService(
				AwsConfig.sesClient(),
				AwsConfig.sqsClient(),
				AwsConfig.objectMapper(),
				AwsConfig.snsClient()
		);
		this.taskService = new TaskService(AwsConfig.dynamoDbClient(), notificationService,AwsConfig.objectMapper(),AwsConfig.sfnClient());
		this.tableName = System.getenv("TASKS_TABLE_NAME");
		this.snsTopicArn = System.getenv("ASSIGNMENT_TOPIC_ARN");

		// Validate environment variables at initialization
		if (tableName == null || tableName.isEmpty()) {
			throw new IllegalStateException("TASKS_TABLE_NAME environment variable is not set");
		}
		if (snsTopicArn == null || snsTopicArn.isEmpty()) {
			throw new IllegalStateException("TASKS_SNS_TOPIC_ARN environment variable is not set");
		}
	}

	@Override
	public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
		LambdaLogger logger = context.getLogger();
		APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent()
				.withHeaders(CORS_HEADERS);

		try {
			// Extract task ID from path parameters
			Map<String, String> pathParameters = input.getPathParameters();
			if (pathParameters == null || !pathParameters.containsKey("taskId")) {
				logger.log("ERROR: Missing taskId path parameter");
				return response.withStatusCode(400)
						.withBody("{\"error\":\"taskId path parameter is required\"}");
			}

			String taskId = pathParameters.get("taskId").trim();
			if (taskId.isEmpty()) {
				logger.log("ERROR: Empty taskId provided");
				return response.withStatusCode(400)
						.withBody("{\"error\":\"taskId cannot be empty\"}");
			}

			// Attempt to delete the task with explicit error handling
			try {
				taskService.deleteTask(taskId, tableName, snsTopicArn);
			} catch (ConditionalCheckFailedException e) {
				logger.log("ERROR: Task not found: " + e.getMessage());
				return response.withStatusCode(404)
						.withBody("{\"error\":\"Task not found\"}");
			} catch (ResourceNotFoundException e) {
				logger.log("ERROR: DynamoDB table not found: " + e.getMessage());
				return response.withStatusCode(404)
						.withBody("{\"error\":\"DynamoDB table not found\"}");
			} catch (IllegalArgumentException e) {
				logger.log("ERROR: Invalid input: " + e.getMessage());
				return response.withStatusCode(400)
						.withBody(String.format("{\"error\":\"%s\"}", e.getMessage()));
			} catch (Exception e) {
				logger.log("ERROR: Task deletion failed: " + e.getMessage());
				throw e; // Let the outer catch handle unexpected errors
			}

			return response.withStatusCode(200)
					.withBody("{\"status\":\"success\",\"message\":\"Task deleted successfully\"}");

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
