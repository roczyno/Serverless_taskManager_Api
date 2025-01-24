package com.roczyno.aws.task_manager;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.roczyno.aws.task_manager.config.AwsConfig;
import com.roczyno.aws.task_manager.service.NotificationService;
import com.roczyno.aws.task_manager.service.TaskService;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class DeadlineCheckerHandler implements RequestHandler<ScheduledEvent, Void> {
	private TaskService taskService;
	private final String tasksTableName;
	private final String deadlineTopicArn;
	private final String stepFunctionArn;
	private final String expiredTasksQueueUrl;
	private final String initId;

	public DeadlineCheckerHandler() {
		initId = "INIT-" + System.currentTimeMillis();
		LambdaLogger logger = getInitLogger();
		logger.log(String.format("[InitID: %s] Starting handler initialization", initId));

		try {
			Map<String, String> envVars = System.getenv();
			logEnvironmentVariables(envVars, logger);

			// Initialize configuration
			this.tasksTableName = envVars.get("TASKS_TABLE_NAME");
			this.deadlineTopicArn = envVars.get("DEADLINE_TOPIC_ARN");
			this.stepFunctionArn = envVars.get("TASK_DEADLINE_STATE_MACHINE_ARN");
			this.expiredTasksQueueUrl = envVars.get("EXPIRED_TASKS_QUEUE_URL");

			validateEnvironmentVariables(logger);
			initializeServices(logger);

			logger.log(String.format("[InitID: %s] Handler initialization completed successfully with configuration:\n" +
							"Table Name: %s\n" +
							"Topic ARN: %s\n" +
							"Step Function ARN: %s\n" +
							"Queue URL: %s\n" +
							"Build Version: %s",
					initId, tasksTableName, deadlineTopicArn, stepFunctionArn,
					expiredTasksQueueUrl, System.getenv("BUILD_VERSION")));

		} catch (Exception e) {
			logger.log(String.format("[InitID: %s] Critical failure during handler initialization: %s",
					initId, e.getMessage()));
			e.printStackTrace();
			throw new RuntimeException("Handler initialization failed", e);
		}
	}

	private void logEnvironmentVariables(Map<String, String> envVars, LambdaLogger logger) {
		List<String> safeKeys = new ArrayList<>(envVars.keySet());
		safeKeys.removeIf(key -> key.toLowerCase().contains("secret") ||
				key.toLowerCase().contains("password") ||
				key.toLowerCase().contains("key"));

		logger.log(String.format("[InitID: %s] Available environment variables (excluding sensitive keys): %s",
				initId, String.join(", ", safeKeys)));
	}

	private void initializeServices(LambdaLogger logger) {
		logger.log(String.format("[InitID: %s] Initializing AWS services...", initId));

		try {
			NotificationService notificationService = new NotificationService(
					AwsConfig.sqsClient(),
					AwsConfig.snsClient()
			);
			logger.log(String.format("[InitID: %s] Successfully initialized NotificationService", initId));

			DynamoDbClient dynamoDbClient = AwsConfig.dynamoDbClient();
			logger.log(String.format("[InitID: %s] Successfully initialized DynamoDbClient", initId));

			this.taskService = new TaskService(
					dynamoDbClient,
					notificationService,
					AwsConfig.objectMapper(),
					AwsConfig.sfnClient()
			);
			taskService.setLogger(logger);
			logger.log(String.format("[InitID: %s] Successfully initialized TaskService", initId));

		} catch (Exception e) {
			logger.log(String.format("[InitID: %s] Failed to initialize AWS services: %s",
					initId, e.getMessage()));
			throw new RuntimeException("AWS services initialization failed", e);
		}
	}

	@Override
	public Void handleRequest(ScheduledEvent event, Context context) {
		LambdaLogger logger = context.getLogger();
		String executionId = context.getAwsRequestId();

		LocalDateTime invocationTime = LocalDateTime.ofInstant(
				Instant.ofEpochMilli(event.getTime().getMillis()),
				ZoneId.systemDefault()
		);

		logger.log(String.format("[ExecutionID: %s] DeadlineChecker Lambda triggered at %s with context:\n" +
						"Function name: %s\n" +
						"Memory limit: %d MB\n" +
						"Remaining time: %d ms\n" +
						"Cloud watch log group: %s\n" +
						"Cloud watch log stream: %s",
				executionId, invocationTime,
				context.getFunctionName(),
				context.getMemoryLimitInMB(),
				context.getRemainingTimeInMillis(),
				context.getLogGroupName(),
				context.getLogStreamName()));

		try {
			processApproachingDeadlines(executionId, context);
			processExpiredTasksBatch(executionId, context);

			logger.log(String.format("[ExecutionID: %s] Lambda execution completed successfully.\n" +
							"Final remaining time: %dms\n" +
							"Memory used: %d MB",
					executionId,
					context.getRemainingTimeInMillis(),
					Runtime.getRuntime().totalMemory() / (1024 * 1024)));

		} catch (Exception e) {
			logger.log(String.format("[ExecutionID: %s] Lambda execution failed with error: %s",
					executionId, e.getMessage()));
			e.printStackTrace();
			throw new RuntimeException("DeadlineChecker execution failed", e);
		}

		return null;
	}

	private void processApproachingDeadlines(String executionId, Context context) {
		LambdaLogger logger = context.getLogger();
		logger.log(String.format("[ExecutionID: %s] Starting deadline check phase. Remaining time: %dms",
				executionId, context.getRemainingTimeInMillis()));

		long startTime = System.currentTimeMillis();
		try {
			taskService.notifyApproachingDeadlines(tasksTableName, deadlineTopicArn);

			long duration = System.currentTimeMillis() - startTime;
			logger.log(String.format("[ExecutionID: %s] Deadline check phase completed in %dms.\n" +
							"Remaining time: %dms",
					executionId, duration, context.getRemainingTimeInMillis()));

		} catch (Exception e) {
			logger.log(String.format("[ExecutionID: %s] Deadline check phase failed after %dms: %s",
					executionId, System.currentTimeMillis() - startTime, e.getMessage()));
			throw e;
		}
	}

	private void processExpiredTasksBatch(String executionId, Context context) {
		LambdaLogger logger = context.getLogger();
		long remainingTime = context.getRemainingTimeInMillis();
		logger.log(String.format("[ExecutionID: %s] Starting expired tasks processing. Remaining time: %dms",
				executionId, remainingTime));

		if (remainingTime < 10000) {
			logger.log(String.format("[ExecutionID: %s] Insufficient time remaining (%dms) to process expired tasks. " +
							"Minimum required: 10000ms. Skipping phase.",
					executionId, remainingTime));
			return;
		}

		long startTime = System.currentTimeMillis();
		try {
			taskService.processExpiredTasks(tasksTableName, stepFunctionArn);

			long duration = System.currentTimeMillis() - startTime;
			logger.log(String.format("[ExecutionID: %s] Expired tasks processing completed in %dms.\n" +
							"Final remaining time: %dms",
					executionId, duration, context.getRemainingTimeInMillis()));

		} catch (Exception e) {
			logger.log(String.format("[ExecutionID: %s] Expired tasks processing failed after %dms: %s",
					executionId, System.currentTimeMillis() - startTime, e.getMessage()));
			throw e;
		}
	}

	private void validateEnvironmentVariables(LambdaLogger logger) {
		List<String> missingVariables = new ArrayList<>();
		Map<String, String> envVars = System.getenv();

		logger.log("Starting environment variables validation");

		validateVariable(missingVariables, "TASKS_TABLE_NAME", tasksTableName, logger);
		validateVariable(missingVariables, "DEADLINE_TOPIC_ARN", deadlineTopicArn, logger);
		validateVariable(missingVariables, "TASK_DEADLINE_STATE_MACHINE_ARN", stepFunctionArn, logger);
		validateVariable(missingVariables, "EXPIRED_TASKS_QUEUE_URL", expiredTasksQueueUrl, logger);

		if (!missingVariables.isEmpty()) {
			String errorMessage = String.format("Missing required environment variables: %s. " +
							"Available variables: %s",
					String.join(", ", missingVariables),
					String.join(", ", envVars.keySet()));
			logger.log(errorMessage);
			throw new IllegalStateException(errorMessage);
		}

		logger.log("Environment variables validation successful");
	}

	private void validateVariable(List<String> missingVariables, String varName, String varValue, LambdaLogger logger) {
		if (varValue == null || varValue.trim().isEmpty()) {
			logger.log(String.format("Environment variable %s is missing or empty", varName));
			missingVariables.add(varName);
		} else {
			logger.log(String.format("Environment variable %s validated successfully", varName));
		}
	}

	private LambdaLogger getInitLogger() {

		return new LambdaLogger() {
			@Override
			public void log(String message) {
				System.out.println(message);
			}

			@Override
			public void log(byte[] message) {
				System.out.println(new String(message));
			}
		};
	}
}
