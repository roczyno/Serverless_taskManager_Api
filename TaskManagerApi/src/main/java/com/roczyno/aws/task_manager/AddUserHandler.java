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
import com.roczyno.aws.task_manager.model.Role;
import com.roczyno.aws.task_manager.model.User;
import com.roczyno.aws.task_manager.service.CognitoUserService;
import com.roczyno.aws.task_manager.service.NotificationService;
import software.amazon.awssdk.awscore.exception.AwsServiceException;

import java.util.Map;

public class AddUserHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
	private final CognitoUserService cognitoUserService;
	private final String userPoolId;
	private static final Map<String, String> CORS_HEADERS = Map.of(
			"Content-Type", "application/json",
			"Access-Control-Allow-Origin", "*",
			"Access-Control-Allow-Methods", "POST",
			"Access-Control-Allow-Headers", "Content-Type,X-Amz-Date,Authorization,X-Api-Key,X-Amz-Security-Token"
	);


	public AddUserHandler() {
		// Create NotificationService with dependencies from AwsConfig
		NotificationService notificationService = new NotificationService(
				AwsConfig.sesClient(),
				AwsConfig.sqsClient(),
				AwsConfig.objectMapper(),
				AwsConfig.snsClient()

		);

		this.cognitoUserService = new CognitoUserService(
				System.getenv("AWS_REGION"),
				notificationService
		);
		this.userPoolId = System.getenv("TM_COGNITO_USER_POOL_ID");
	}
	@Override
	public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {


		APIGatewayProxyResponseEvent response= new APIGatewayProxyResponseEvent()
				.withHeaders(CORS_HEADERS);

		LambdaLogger logger= context.getLogger();
		try{

			String requestBody=input.getBody();
			JsonObject userDetails=JsonParser.parseString(requestBody).getAsJsonObject();
			User user= new User();
			user.setEmail(userDetails.get("email").getAsString());
			user.setFirstName(userDetails.get("firstName").getAsString());
			user.setLastName(userDetails.get("lastName").getAsString());
			user.setRole(Role.valueOf(userDetails.get("role").getAsString()));
			JsonObject addUserResponse=cognitoUserService.adminAddUser(user,userPoolId);
			response.withBody(new Gson().toJson(addUserResponse,JsonObject.class));
			response.withStatusCode(201);
		}
		catch (AwsServiceException ex){
			logger.log(ex.awsErrorDetails().errorMessage());
			ErrorResponse errorResponse= new ErrorResponse(ex.awsErrorDetails().errorMessage());
			String errorResponseJsonString=new GsonBuilder().serializeNulls().create().toJson(errorResponse,ErrorResponse.class);
			response.withBody(errorResponseJsonString);
			response.withStatusCode(500);


		}catch (Exception ex){
			logger.log(ex.getMessage());
			ErrorResponse errorResponse= new ErrorResponse(ex.getMessage());
			String errorResponseJsonString=new GsonBuilder().serializeNulls().create().toJson(errorResponse,ErrorResponse.class);
			response.withBody(errorResponseJsonString);
			response.withStatusCode(500);
		}
		return response;
	}
}
