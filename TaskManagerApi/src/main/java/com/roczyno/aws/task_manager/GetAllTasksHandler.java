package com.roczyno.aws.task_manager;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.roczyno.aws.task_manager.config.AwsConfig;
import com.roczyno.aws.task_manager.model.LocalDateTimeAdapter;
import com.roczyno.aws.task_manager.model.Task;
import com.roczyno.aws.task_manager.service.NotificationService;
import com.roczyno.aws.task_manager.service.QueueService;
import com.roczyno.aws.task_manager.service.TaskService;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GetAllTasksHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
	private final TaskService taskService;
	private final String tableName;

	private static final Map<String, String> CORS_HEADERS = Map.of(
			"Content-Type", "application/json",
			"Access-Control-Allow-Origin", "*",
			"Access-Control-Allow-Methods", "GET",
			"Access-Control-Allow-Headers", "Content-Type,X-Amz-Date,Authorization,X-Api-Key,X-Amz-Security-Token"
	);

	public GetAllTasksHandler() {
		try {
			NotificationService notificationService = new NotificationService(AwsConfig.snsClient());
			QueueService queueService=new QueueService(AwsConfig.sqsClient());
			this.taskService = new TaskService(AwsConfig.dynamoDbClient(), notificationService,queueService,
					AwsConfig.objectMapper(),AwsConfig.sfnClient());
			this.tableName = System.getenv("TASKS_TABLE_NAME");

			// Enhanced environment variable validation
			if (tableName == null || tableName.isEmpty()) {
				throw new IllegalStateException("TASKS_TABLE_NAME environment variable is not set");
			}

			// Verify DynamoDB client initialization
			if (AwsConfig.dynamoDbClient() == null) {
				throw new IllegalStateException("DynamoDB client failed to initialize");
			}
		} catch (Exception e) {
			throw new RuntimeException("Failed to initialize GetAllTasksHandler: " + e.getMessage(), e);
		}
	}

	@Override
	public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
		LambdaLogger logger = context.getLogger();
		APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent()
				.withHeaders(CORS_HEADERS);

		try {
			logger.log("Starting getAllTasks request...");
			logger.log("Using table name: " + tableName);


			logger.log("Checking AWS credentials...");
			try {
				DescribeTableRequest describeTableRequest = DescribeTableRequest.builder()
						.tableName(tableName)
						.build();
				AwsConfig.dynamoDbClient().describeTable(describeTableRequest);
				logger.log("DynamoDB table access verified successfully");
			} catch (Exception e) {
				logger.log("ERROR: Failed to access DynamoDB table: " + e.getMessage());
				return response.withStatusCode(500)
						.withBody("{\"error\":\"Database access error\",\"details\":\"" + e.getMessage() + "\"}");
			}

			List<Task> tasks = taskService.getAllTasks(tableName);
			logger.log("Successfully retrieved " + tasks.size() + " tasks");
			Gson gson = new GsonBuilder()
					.registerTypeAdapter(LocalDateTime.class, new LocalDateTimeAdapter())
					.create();

			String responseBody = gson.toJson(Map.of("tasks", tasks));
			return response.withStatusCode(200).withBody(responseBody);

		} catch (Exception e) {
			logger.log("ERROR: Failed to retrieve tasks. Details below:");
			logger.log("Error type: " + e.getClass().getName());
			logger.log("Error message: " + e.getMessage());
			logger.log("Stack trace: " + getStackTraceAsString(e));

			String errorMessage = formatErrorResponse(e);
			return response.withStatusCode(500)
					.withBody(errorMessage);
		}
	}


	private String getStackTraceAsString(Exception e) {
		StringWriter sw = new StringWriter();
		PrintWriter pw = new PrintWriter(sw);
		e.printStackTrace(pw);
		return sw.toString();
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
