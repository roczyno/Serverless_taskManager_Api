// DeadlineCheckerHandler.java
package com.roczyno.aws.task_manager;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.roczyno.aws.task_manager.config.AwsConfig;
import com.roczyno.aws.task_manager.service.NotificationService;
import com.roczyno.aws.task_manager.service.TaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class DeadlineCheckerHandler implements RequestHandler<ScheduledEvent, Void> {
	private static final Logger logger = LoggerFactory.getLogger(DeadlineCheckerHandler.class);
	private final TaskService taskService;
	private final String tasksTableName;
	private final String deadlineTopicArn;
	private final String stepFunctionArn;
	private final String expiredTasksQueueUrl;

	public DeadlineCheckerHandler() {
		logger.info("Initializing DeadlineCheckerHandler with build version: {}", System.getenv("BUILD_VERSION"));
		try {
			Map<String, String> envVars = System.getenv();
			logger.debug("Environment variables available: {}", envVars.keySet());

			// Initialize configuration
			this.tasksTableName = envVars.get("TASKS_TABLE_NAME");
			this.deadlineTopicArn = envVars.get("DEADLINE_TOPIC_ARN");
			this.stepFunctionArn = envVars.get("TASK_DEADLINE_STATE_MACHINE_ARN");
			this.expiredTasksQueueUrl = envVars.get("EXPIRED_TASKS_QUEUE_URL");

			// Validate required environment variables
			validateEnvironmentVariables();

			logger.info("Initializing AWS clients...");
			// Initialize services with enhanced logging
			try {
				NotificationService notificationService = new NotificationService(
						AwsConfig.sesClient(),
						AwsConfig.sqsClient(),
						AwsConfig.objectMapper(),
						AwsConfig.snsClient()
				);
				logger.info("Successfully initialized NotificationService");

				DynamoDbClient dynamoDbClient = AwsConfig.dynamoDbClient();
				logger.info("Successfully initialized DynamoDbClient");

				this.taskService = new TaskService(
						dynamoDbClient,
						notificationService,
						AwsConfig.objectMapper(),
						AwsConfig.sfnClient()
				);
				logger.info("Successfully initialized TaskService");
			} catch (Exception e) {
				logger.error("Failed to initialize AWS services: {}", e.getMessage(), e);
				throw new RuntimeException("AWS services initialization failed", e);
			}

			logger.info("Handler initialization completed successfully. Configuration: \n" +
							"Table Name: {}\n" +
							"Topic ARN: {}\n" +
							"Step Function ARN: {}\n" +
							"Queue URL: {}",
					tasksTableName, deadlineTopicArn, stepFunctionArn, expiredTasksQueueUrl);

		} catch (Exception e) {
			logger.error("Critical failure during handler initialization: {}", e.getMessage(), e);
			logger.error("Stack trace: ", e);
			throw new RuntimeException("Handler initialization failed", e);
		}
	}

	@Override
	public Void handleRequest(ScheduledEvent event, Context context) {
		String executionId = context.getAwsRequestId();
		logger.info("[ExecutionID: {}] DeadlineChecker Lambda triggered at {} with context: {}",
				executionId, event.getTime(), context);
		logger.info("[ExecutionID: {}] Initial remaining time: {}ms",
				executionId, context.getRemainingTimeInMillis());

		try {
			// Process tasks approaching deadline
			logger.info("[ExecutionID: {}] Starting deadline check phase", executionId);
			long startTime = System.currentTimeMillis();

			try {
				taskService.notifyApproachingDeadlines(tasksTableName, deadlineTopicArn);
				logger.info("[ExecutionID: {}] Deadline check phase completed in {}ms",
						executionId, System.currentTimeMillis() - startTime);
			} catch (Exception e) {
				logger.error("[ExecutionID: {}] Deadline check phase failed: {}",
						executionId, e.getMessage(), e);
				throw e;
			}

			// Process expired tasks
			long remainingTime = context.getRemainingTimeInMillis();
			logger.info("[ExecutionID: {}] Remaining time before expired tasks processing: {}ms",
					executionId, remainingTime);

			if (remainingTime < 10000) {
				logger.warn("[ExecutionID: {}] Insufficient time remaining ({}ms) to process expired tasks. Skipping phase.",
						executionId, remainingTime);
				return null;
			}

			try {
				startTime = System.currentTimeMillis();
				logger.info("[ExecutionID: {}] Starting expired tasks processing phase", executionId);
				taskService.processExpiredTasks(tasksTableName, stepFunctionArn);
				logger.info("[ExecutionID: {}] Expired tasks processing completed in {}ms",
						executionId, System.currentTimeMillis() - startTime);
			} catch (Exception e) {
				logger.error("[ExecutionID: {}] Expired tasks processing failed: {}",
						executionId, e.getMessage(), e);
				throw e;
			}

			logger.info("[ExecutionID: {}] Lambda execution completed successfully. Final remaining time: {}ms",
					executionId, context.getRemainingTimeInMillis());

		} catch (Exception e) {
			logger.error("[ExecutionID: {}] Lambda execution failed with error: {}",
					executionId, e.getMessage(), e);
			logger.error("[ExecutionID: {}] Full stack trace: ", executionId, e);
			throw new RuntimeException("DeadlineChecker execution failed", e);
		}

		return null;
	}

	private void validateEnvironmentVariables() {
		List<String> missingVariables = new ArrayList<>();
		Map<String, String> envVars = System.getenv();

		logger.debug("Validating environment variables. Available variables: {}", envVars.keySet());

		validateVariable(missingVariables, "TASKS_TABLE_NAME", tasksTableName);
		validateVariable(missingVariables, "DEADLINE_TOPIC_ARN", deadlineTopicArn);
		validateVariable(missingVariables, "TASK_DEADLINE_STATE_MACHINE_ARN", stepFunctionArn);
		validateVariable(missingVariables, "EXPIRED_TASKS_QUEUE_URL", expiredTasksQueueUrl);

		if (!missingVariables.isEmpty()) {
			String errorMessage = String.format("Missing required environment variables: %s. Available variables: %s",
					String.join(", ", missingVariables),
					String.join(", ", envVars.keySet()));
			logger.error(errorMessage);
			throw new IllegalStateException(errorMessage);
		}

		logger.info("Environment variables validation successful");
	}

	private void validateVariable(List<String> missingVariables, String varName, String varValue) {
		if (varValue == null || varValue.isEmpty()) {
			logger.error("Environment variable {} is missing or empty", varName);
			missingVariables.add(varName);
		} else {
			logger.debug("Environment variable {} validated successfully", varName);
		}
	}
}
