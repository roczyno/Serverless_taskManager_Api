package com.roczyno.aws.task_manager;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roczyno.aws.task_manager.config.AwsConfig;
import com.roczyno.aws.task_manager.model.Task;
import com.roczyno.aws.task_manager.service.NotificationService;
import com.roczyno.aws.task_manager.service.QueueService;
import com.roczyno.aws.task_manager.service.TaskService;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class TaskExpirationHandler implements RequestHandler<SQSEvent, Void> {

	private final String stepFunctionArn;
	private final ObjectMapper objectMapper;
	private final TaskService taskService;
	private final String handlerId;
	private volatile LambdaLogger logger;

	public TaskExpirationHandler() {
		this.handlerId = "HANDLER-" + UUID.randomUUID().toString();
		this.logger = getInitLogger();

		try {
			logger.log(String.format("[HandlerID: %s] Initializing TaskExpirationHandler", handlerId));
			logger.log(String.format("[HandlerID: %s] Starting environment validation", handlerId));

			// Validate environment variables
			this.stepFunctionArn = validateEnvironmentVariable("TASK_DEADLINE_STATE_MACHINE_ARN");
			logger.log(String.format("[HandlerID: %s] Step Function ARN validated: %s",
					handlerId, stepFunctionArn));

			// Initialize services
			logger.log(String.format("[HandlerID: %s] Initializing AWS services and dependencies", handlerId));

			this.objectMapper = AwsConfig.objectMapper();
			logger.log(String.format("[HandlerID: %s] ObjectMapper initialized", handlerId));

			NotificationService notificationService = new NotificationService(AwsConfig.snsClient());
			logger.log(String.format("[HandlerID: %s] NotificationService initialized", handlerId));

			QueueService queueService = new QueueService(AwsConfig.sqsClient());
			logger.log(String.format("[HandlerID: %s] QueueService initialized", handlerId));

			DynamoDbClient dynamoDbClient = AwsConfig.dynamoDbClient();
			logger.log(String.format("[HandlerID: %s] DynamoDB client initialized", handlerId));

			this.taskService = new TaskService(
					dynamoDbClient,
					notificationService,
					queueService,
					objectMapper,
					AwsConfig.sfnClient()
			);
			logger.log(String.format("[HandlerID: %s] TaskService initialized successfully", handlerId));
			logger.log(String.format("[HandlerID: %s] Handler initialization completed successfully", handlerId));
			this.taskService.setLogger(logger);
		} catch (Exception e) {
			String errorMessage = String.format("[HandlerID: %s] Critical error during handler initialization: %s\nStack trace: %s",
					handlerId, e.getMessage(), e.getStackTrace());
			logger.log(errorMessage);
			throw new RuntimeException(errorMessage, e);
		}
	}

	@Override
	public Void handleRequest(SQSEvent event, Context context) {
		synchronized(this) {
			this.logger = context.getLogger();
			this.taskService.setLogger(logger);
		}

		String executionId = context.getAwsRequestId();
		long startTime = System.currentTimeMillis();

		logger.log(String.format("[HandlerID: %s][ExecutionID: %s] Starting request processing",
				handlerId, executionId));
		logger.log(String.format("[HandlerID: %s][ExecutionID: %s] Received %d messages to process",
				handlerId, executionId, event.getRecords().size()));

		List<MessageError> failedMessages = new ArrayList<>();
		int processedCount = 0;

		for (SQSEvent.SQSMessage record : event.getRecords()) {
			long messageStartTime = System.currentTimeMillis();
			String messageId = record.getMessageId();

			try {
				logger.log(String.format("[HandlerID: %s][ExecutionID: %s][MessageID: %s] Starting message processing",
						handlerId, executionId, messageId));

				// Validate message body
				if (record.getBody() == null || record.getBody().trim().isEmpty()) {
					throw new IllegalArgumentException("Message body is null or empty");
				}

				logger.log(String.format("[HandlerID: %s][ExecutionID: %s][MessageID: %s] Message body: %s",
						handlerId, executionId, messageId, record.getBody()));

				Task task = objectMapper.readValue(record.getBody(), Task.class);

				// Validate task object
				validateTask(task);

				logger.log(String.format("[HandlerID: %s][ExecutionID: %s][MessageID: %s][TaskID: %s] Task deserialized successfully",
						handlerId, executionId, messageId, task.getId()));

				taskService.startExpirationWorkflow(task, stepFunctionArn);
				processedCount++;

				long processingTime = System.currentTimeMillis() - messageStartTime;
				logger.log(String.format("[HandlerID: %s][ExecutionID: %s][MessageID: %s][TaskID: %s] Message processed successfully in %d ms",
						handlerId, executionId, messageId, task.getId(), processingTime));

			} catch (Exception e) {
				MessageError error = new MessageError(
						messageId,
						e.getClass().getSimpleName(),
						e.getMessage(),
						record.getBody(),
						getStackTraceAsString(e)
				);
				failedMessages.add(error);

				logger.log(String.format("[HandlerID: %s][ExecutionID: %s][MessageID: %s] Error processing message:\n" +
								"Error Type: %s\n" +
								"Error Message: %s\n" +
								"Message Body: %s\n" +
								"Stack Trace: %s",
						handlerId, executionId, messageId,
						error.errorType,
						error.errorMessage,
						error.messageBody,
						error.stackTrace));
			}
		}

		// Log detailed error summary if there are failures
		if (!failedMessages.isEmpty()) {
			StringBuilder errorSummary = new StringBuilder();
			errorSummary.append(String.format("[HandlerID: %s][ExecutionID: %s] Failed Messages Summary:\n",
					handlerId, executionId));

			for (int i = 0; i < failedMessages.size(); i++) {
				MessageError error = failedMessages.get(i);
				errorSummary.append(String.format(
						"Failed Message %d/%d:\n" +
								"- Message ID: %s\n" +
								"- Error Type: %s\n" +
								"- Error Message: %s\n" +
								"- Message Body: %s\n" +
								"- Stack Trace: %s\n",
						i + 1, failedMessages.size(),
						error.messageId,
						error.errorType,
						error.errorMessage,
						error.messageBody,
						error.stackTrace
				));
			}
			logger.log(errorSummary.toString());
		}

		// Log final execution statistics
		long totalProcessingTime = System.currentTimeMillis() - startTime;
		logger.log(String.format("[HandlerID: %s][ExecutionID: %s] Processing completed. Statistics:\n" +
						"- Total messages: %d\n" +
						"- Successfully processed: %d\n" +
						"- Failed: %d\n" +
						"- Total processing time: %d ms\n" +
						"- Average time per message: %.2f ms\n" +
						"- Remaining Lambda execution time: %d ms",
				handlerId, executionId,
				event.getRecords().size(),
				processedCount,
				failedMessages.size(),
				totalProcessingTime,
				event.getRecords().size() > 0 ? (double) totalProcessingTime / event.getRecords().size() : 0,
				context.getRemainingTimeInMillis()));

		return null;
	}

	private void validateTask(Task task) {
		if (task == null) {
			throw new IllegalArgumentException("Task object is null");
		}
		if (task.getId() == null || task.getId().trim().isEmpty()) {
			throw new IllegalArgumentException("Task ID is null or empty");
		}
		// Add more task validation as needed
	}

	private String getStackTraceAsString(Exception e) {
		StringWriter sw = new StringWriter();
		PrintWriter pw = new PrintWriter(sw);
		e.printStackTrace(pw);
		return sw.toString();
	}

	private static class MessageError {
		final String messageId;
		final String errorType;
		final String errorMessage;
		final String messageBody;
		final String stackTrace;

		MessageError(String messageId, String errorType, String errorMessage, String messageBody, String stackTrace) {
			this.messageId = messageId;
			this.errorType = errorType;
			this.errorMessage = errorMessage;
			this.messageBody = messageBody;
			this.stackTrace = stackTrace;
		}
	}

	private String validateEnvironmentVariable(String name) {
		String value = System.getenv(name);
		if (value == null || value.trim().isEmpty()) {
			logger.log(String.format("[HandlerID: %s] Environment variable validation failed: %s is missing or empty",
					handlerId, name));
			throw new IllegalStateException("Missing required environment variable: " + name);
		}
		logger.log(String.format("[HandlerID: %s] Environment variable validated: %s", handlerId, name));
		return value;
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
