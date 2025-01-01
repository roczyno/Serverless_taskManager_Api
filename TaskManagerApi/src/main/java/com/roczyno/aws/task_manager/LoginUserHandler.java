package com.roczyno.aws.task_manager;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.roczyno.aws.task_manager.service.CognitoUserService;
import software.amazon.awssdk.awscore.exception.AwsServiceException;

import java.util.HashMap;
import java.util.Map;

public class LoginUserHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

	private final CognitoUserService cognitoUserService;
	private final String appClientId;
	private final String appClientSecret;

	public LoginUserHandler(){
		this.cognitoUserService=new CognitoUserService(System.getenv("AWS_REGION"));
		this.appClientId=System.getenv("TM_COGNITO_POOL_CLIENT_ID");
		this.appClientSecret=System.getenv("TM_COGNITO_POOL_SECRET_ID");
	}
	@Override
	public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
		Map<String,String> headers= new HashMap<>();
		headers.put("Content-Type","application/json");

		APIGatewayProxyResponseEvent response= new APIGatewayProxyResponseEvent()
				.withHeaders(headers);
		LambdaLogger logger= context.getLogger();

		try {
			JsonObject loginRequest= JsonParser.parseString(input.getBody()).getAsJsonObject();
			JsonObject loginResult=cognitoUserService.userLogin(loginRequest,appClientId,appClientSecret);
			response.withBody(new Gson().toJson(loginResult,JsonObject.class));
		}catch (AwsServiceException ex){
			logger.log(ex.awsErrorDetails().errorMessage());
			response.withStatusCode(500);
			response.withBody(ex.awsErrorDetails().errorMessage());
			response.withStatusCode(200);

		}catch (Exception ex){
			logger.log(ex.getMessage());
			response.withBody(ex.getMessage());
			response.withStatusCode(500);
		}



		return response;
	}
}
