package com.roczyno.aws.task_manager;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.roczyno.aws.task_manager.config.AwsConfig;
import com.roczyno.aws.task_manager.model.LocalDateTimeAdapter;
import com.roczyno.aws.task_manager.model.Task;
import com.roczyno.aws.task_manager.service.NotificationService;
import com.roczyno.aws.task_manager.service.TaskService;
import software.amazon.awssdk.awscore.exception.AwsServiceException;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.google.common.base.Throwables.getStackTraceAsString;

public class GetTasksByAssignedUserHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
	private final TaskService taskService;
	private final String tableName;

	private static final Map<String, String> CORS_HEADERS = Map.of(
			"Content-Type", "application/json",
			"Access-Control-Allow-Origin", "*",
			"Access-Control-Allow-Methods", "GET",
			"Access-Control-Allow-Headers", "Content-Type,X-Amz-Date,Authorization,X-Api-Key,X-Amz-Security-Token"
	);

	public GetTasksByAssignedUserHandler() {
		this.taskService = new TaskService(AwsConfig.dynamoDbClient(), new NotificationService(
				AwsConfig.sqsClient(),
				AwsConfig.snsClient()
		),AwsConfig.objectMapper(),AwsConfig.sfnClient());
		this.tableName = System.getenv("TASKS_TABLE_NAME");

		if (tableName == null || tableName.isEmpty()) {
			throw new IllegalStateException("TASKS_TABLE_NAME environment variable is not set");
		}
	}

	@Override
	public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
		LambdaLogger logger = context.getLogger();
		APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent()
				.withHeaders(CORS_HEADERS);

		try {

			Map<String, String> params = input.getPathParameters();
			if (params == null || !params.containsKey("userId")) {
				logger.log("Validation failed: Missing userId parameter");
				return response
						.withStatusCode(400)
						.withBody("{\"error\":\"userId parameter is required\"}");
			}

			String userId = params.get("userId");
			logger.log("Retrieving tasks for user: " + userId);


			List<Task> tasks = taskService.getTasksByAssignedUser(userId, tableName);


			Gson gson = new GsonBuilder()
					.registerTypeAdapter(LocalDateTime.class, new LocalDateTimeAdapter())
					.create();
			String tasksJson = gson.toJson(tasks);

			return response
					.withStatusCode(200)
					.withBody(tasksJson);

		} catch (AwsServiceException ex) {
			logger.log(String.format("AWS error during task retrieval. Error Message: %s, Error Code: %s, Service Name: %s, Stack Trace: %s",
					ex.awsErrorDetails().errorMessage(),
					ex.awsErrorDetails().errorCode(),
					ex.awsErrorDetails().serviceName(),
					getStackTraceAsString(ex)));
			return response
					.withStatusCode(502)
					.withBody(String.format("{\"error\":\"%s\"}", ex.awsErrorDetails().errorMessage()));
		} catch (Exception ex) {
			logger.log(String.format("Unexpected error during task retrieval: %s, Stack Trace: %s",
					ex.getMessage(),
					getStackTraceAsString(ex)));
			return response
					.withStatusCode(500)
					.withBody(formatErrorResponse(ex));
		}
	}

	private String formatErrorResponse(Exception e) {
		Map<String, String> errorDetails = new HashMap<>();
		errorDetails.put("error", "Internal server error");
		errorDetails.put("message", "Failed to retrieve tasks");
		errorDetails.put("type", e.getClass().getSimpleName());
		errorDetails.put("details", e.getMessage());

		Gson gson = new GsonBuilder()
				.registerTypeAdapter(LocalDateTime.class, new LocalDateTimeAdapter())
				.create();

		return gson.toJson(errorDetails);
	}
}
