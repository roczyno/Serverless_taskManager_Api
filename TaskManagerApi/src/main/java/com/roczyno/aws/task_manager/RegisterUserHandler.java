package com.roczyno.aws.task_manager;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.roczyno.aws.task_manager.config.AwsConfig;
import com.roczyno.aws.task_manager.service.CognitoUserService;
import com.roczyno.aws.task_manager.service.NotificationService;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ses.SesClient;

import java.util.HashMap;
import java.util.Map;

public class RegisterUserHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

	private final CognitoUserService cognitoUserService;
	private final String appClientId;
	private final String appClientSecret;
	public RegisterUserHandler(){
		NotificationService notificationService = new NotificationService(
				AwsConfig.sesClient(),
				AwsConfig.sqsClient(),
				AwsConfig.objectMapper(),
				AwsConfig.snsClient()

		);
		this.cognitoUserService=new CognitoUserService(System.getenv("AWS_REGION"),notificationService);
		this.appClientId=System.getenv("TM_COGNITO_POOL_CLIENT_ID");
		this.appClientSecret=System.getenv("TM_COGNITO_POOL_SECRET_ID");
	}

	@Override
	public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
		Map<String,String> headers= new HashMap<>();
		headers.put("Content-Type","application/json");

		APIGatewayProxyResponseEvent response= new APIGatewayProxyResponseEvent()
				.withHeaders(headers);

		String requestBody=input.getBody();
		LambdaLogger logger= context.getLogger();
		logger.log("Original json body:"+ requestBody);
		JsonObject userDetails=JsonParser.parseString(requestBody).getAsJsonObject();

		try{
		JsonObject createdUser=cognitoUserService.createUser(userDetails,appClientId,appClientSecret);
		response.withStatusCode(200);
		response.withBody(new Gson().toJson(createdUser,JsonObject.class));
		}catch (AwsServiceException ex){
			logger.log(ex.awsErrorDetails().errorMessage());
			response.withStatusCode(500);
			response.withBody(ex.awsErrorDetails().errorMessage());

		}

		return response;
	}
}
