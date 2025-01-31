package com.roczyno.aws.task_manager;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.roczyno.aws.task_manager.config.AwsConfig;
import com.roczyno.aws.task_manager.service.NotificationService;
import com.roczyno.aws.task_manager.service.QueueService;
import com.roczyno.aws.task_manager.service.TaskService;
import com.roczyno.aws.task_manager.util.AuthorizationUtil;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;

public class ReopenTaskHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
	private final TaskService taskService;
	private final String tableName;
	private final String snsTopicArn;
	private static final Map<String, String> CORS_HEADERS = Map.of(
			"Content-Type", "application/json",
			"Access-Control-Allow-Origin", "*",
			"Access-Control-Allow-Methods", "POST",
			"Access-Control-Allow-Headers", "Content-Type,X-Amz-Date,Authorization,X-Api-Key,X-Amz-Security-Token"
	);

	public ReopenTaskHandler() {
		try {
			this.tableName = System.getenv("TASKS_TABLE_NAME");
			this.snsTopicArn = System.getenv("REOPENED_TOPIC_ARN");


			System.out.println("TASKS_TABLE_NAME value: " + (tableName != null ? tableName : "null"));
			System.out.println("ASSIGNMENT_TOPIC_ARN value: " + (snsTopicArn != null ? snsTopicArn : "null"));

			if (tableName == null || tableName.isEmpty()) {
				throw new IllegalStateException("TASKS_TABLE_NAME environment variable is not set or empty");
			}
			if (snsTopicArn == null || snsTopicArn.isEmpty()) {
				throw new IllegalStateException("ASSIGNMENT_TOPIC_ARN environment variable is not set or empty");
			}

			NotificationService notificationService = new NotificationService(AwsConfig.snsClient());
			QueueService queueService=new QueueService(AwsConfig.sqsClient());
			this.taskService = new TaskService(AwsConfig.dynamoDbClient(), notificationService,queueService,
					AwsConfig.objectMapper(), AwsConfig.sfnClient());

		} catch (Exception e) {
			String errorMessage = "Failed to initialize ReopenTaskHandler: " + e.getMessage();
			System.err.println(errorMessage);
			e.printStackTrace();
			throw new RuntimeException(errorMessage, e);
		}
	}

	@Override
	public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
		LambdaLogger logger = context.getLogger();
		APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent()
				.withHeaders(CORS_HEADERS);

		try {

			if (!AuthorizationUtil.isAdmin(input)) {
				return AuthorizationUtil.forbidden();
			}

			logger.log("Starting task reopening request processing");
			logger.log("Request body: " + input.getBody());

			// Validate input
			if (input.getBody() == null) {
				logger.log("ERROR: Request body is null");
				return response.withStatusCode(400)
						.withBody("{\"error\":\"Request body is required\"}");
			}

			JsonObject taskRequest = JsonParser.parseString(input.getBody()).getAsJsonObject();

			// Validate taskId
			if (!taskRequest.has("taskId") || taskRequest.get("taskId").getAsString().isEmpty()) {
				logger.log("ERROR: Missing or empty taskId in request");
				return response.withStatusCode(400)
						.withBody("{\"error\":\"Task ID is required\"}");
			}

			String taskId = taskRequest.get("taskId").getAsString();

			logger.log(String.format("Processing reopening request for Task ID: %s", taskId));
			logger.log("Using table: " + tableName);
			logger.log("Using SNS topic: " + snsTopicArn);

			taskService.reopenTask(taskId, tableName, snsTopicArn);

			logger.log("Task reopening completed successfully");
			return response.withStatusCode(200)
					.withBody("{\"status\":\"success\",\"message\":\"Task reopened successfully\"}");

		} catch (ConditionalCheckFailedException e) {
			logger.log("ERROR: Task not found or condition check failed");
			logger.log("Exception details: " + e.getMessage());
			logger.log("Stack trace: " + getStackTraceAsString(e));
			return response.withStatusCode(404)
					.withBody("{\"error\":\"Task not found\"}");

		} catch (AwsServiceException e) {
			logger.log("ERROR: AWS service error during reopening");
			logger.log("Error Code: " + e.awsErrorDetails().errorCode());
			logger.log("Error Message: " + e.awsErrorDetails().errorMessage());
			logger.log("Service Name: " + e.awsErrorDetails().serviceName());
			logger.log("Stack trace: " + getStackTraceAsString(e));
			return response.withStatusCode(502)
					.withBody(String.format("{\"error\":\"AWS service error: %s\"}", e.awsErrorDetails().errorMessage()));

		} catch (IllegalArgumentException e) {
			logger.log("ERROR: Invalid input parameter");
			logger.log("Exception details: " + e.getMessage());
			logger.log("Stack trace: " + getStackTraceAsString(e));
			return response.withStatusCode(400)
					.withBody(String.format("{\"error\":\"%s\"}", e.getMessage()));

		} catch (Exception e) {
			logger.log("ERROR: Unexpected error during task reopening");
			logger.log("Exception type: " + e.getClass().getName());
			logger.log("Exception message: " + e.getMessage());
			logger.log("Stack trace: " + getStackTraceAsString(e));
			return response.withStatusCode(500)
					.withBody("{\"error\":\"Internal server error\"}");
		}
	}

	private String getStackTraceAsString(Exception e) {
		StringWriter sw = new StringWriter();
		PrintWriter pw = new PrintWriter(sw);
		e.printStackTrace(pw);
		return sw.toString();
	}
}
