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

public class ConfirmUserHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
	private final CognitoUserService cognitoUserService;
	private final String appClientId;
	private final String appClientSecret;
	private static final Map<String, String> CORS_HEADERS = Map.of(
			"Content-Type", "application/json",
			"Access-Control-Allow-Origin", "*",
			"Access-Control-Allow-Methods", "POST",
			"Access-Control-Allow-Headers", "Content-Type,X-Amz-Date,Authorization,X-Api-Key,X-Amz-Security-Token"
	);

	public ConfirmUserHandler(){
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


		APIGatewayProxyResponseEvent response= new APIGatewayProxyResponseEvent()
				.withHeaders(CORS_HEADERS);

		LambdaLogger logger= context.getLogger();

		try{
			String requestBodyJsonString=input.getBody();
			JsonObject requestBody= JsonParser.parseString(requestBodyJsonString).getAsJsonObject();
			String email=requestBody.get("email").getAsString();
			String confirmationCode=requestBody.get("code").getAsString();

		JsonObject confirmUserResult=cognitoUserService.confirmUserSignUp(appClientId,appClientSecret,email,confirmationCode);
		response.withStatusCode(200);
		response.withBody(new Gson().toJson(confirmUserResult,JsonObject.class));
		}
		catch (AwsServiceException ex){
			logger.log(ex.awsErrorDetails().errorMessage());
			response.withStatusCode(500);
			response.withBody(ex.awsErrorDetails().errorMessage());

		}catch (Exception ex){
			logger.log(ex.getMessage());
			response.withBody(ex.getMessage());
			response.withStatusCode(500);
		}
		return response;
	}
}
