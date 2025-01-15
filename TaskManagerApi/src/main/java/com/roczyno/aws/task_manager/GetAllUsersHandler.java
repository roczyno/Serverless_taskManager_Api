package com.roczyno.aws.task_manager;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.roczyno.aws.task_manager.config.AwsConfig;
import com.roczyno.aws.task_manager.service.CognitoUserService;
import com.roczyno.aws.task_manager.service.NotificationService;
import software.amazon.awssdk.awscore.exception.AwsServiceException;

import java.util.HashMap;
import java.util.Map;

public class GetAllUsersHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
	private final CognitoUserService cognitoUserService;
	private final String userPoolId;


	public GetAllUsersHandler() {
		NotificationService notificationService = new NotificationService(
				AwsConfig.sesClient(),
				AwsConfig.sqsClient(),
				AwsConfig.objectMapper(),
				AwsConfig.snsClient()

		);
		this.cognitoUserService=new CognitoUserService(System.getenv("AWS_REGION"),notificationService);
		this.userPoolId = System.getenv("USER_POOL_ID");
	}

	@Override
	public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
		LambdaLogger logger = context.getLogger();


		Map<String, String> headers = new HashMap<>();
		headers.put("Content-Type", "application/json");
		headers.put("Access-Control-Allow-Origin", "*");
		headers.put("Access-Control-Allow-Headers", "Content-Type,X-Amz-Date,Authorization,X-Api-Key,X-Amz-Security-Token");
		headers.put("Access-Control-Allow-Methods", "GET,OPTIONS");

		APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent()
				.withHeaders(headers);

		try {

			// Get all users
			logger.log("Getting users from user pool: " + userPoolId);
			JsonObject allUsers = cognitoUserService.getAllUsers(userPoolId);
			logger.log("Users retrieved successfully");

			return response
					.withStatusCode(200)
					.withBody(new Gson().toJson(allUsers));

		} catch (AwsServiceException ex) {
			logger.log("AWS Service Exception: " + ex.getMessage());
			logger.log("Error Details: " + ex.awsErrorDetails().errorMessage());

			ErrorResponse errorResponse = new ErrorResponse(
					"AWS Service Error: " + ex.awsErrorDetails().errorMessage()
			);

			return response
					.withStatusCode(500)
					.withBody(new GsonBuilder()
							.serializeNulls()
							.create()
							.toJson(errorResponse));

		} catch (Exception ex) {
			logger.log("General Exception: " + ex.getMessage());
			ex.printStackTrace();

			ErrorResponse errorResponse = new ErrorResponse(
					"Internal Error: " + ex.getMessage()
			);

			return response
					.withStatusCode(500)
					.withBody(new GsonBuilder()
							.serializeNulls()
							.create()
							.toJson(errorResponse));
		}
	}


}
