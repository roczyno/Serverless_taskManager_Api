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
import com.roczyno.aws.task_manager.service.CognitoUserService;
import software.amazon.awssdk.awscore.exception.AwsServiceException;

import java.util.Map;

public class LoginUserHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

	private final CognitoUserService cognitoUserService;
	private final String appClientId;
	private final String appClientSecret;
	private static final Map<String, String> CORS_HEADERS = Map.of(
			"Content-Type", "application/json",
			"Access-Control-Allow-Origin", "*",
			"Access-Control-Allow-Methods", "POST",
			"Access-Control-Allow-Headers", "Content-Type,X-Amz-Date,Authorization,X-Api-Key,X-Amz-Security-Token"
	);

	public LoginUserHandler(){
		this.cognitoUserService=new CognitoUserService(System.getenv("AWS_REGION"),AwsConfig.cognitoIdentityProviderClient());
		this.appClientId=System.getenv("TM_COGNITO_POOL_CLIENT_ID");
		this.appClientSecret=System.getenv("TM_COGNITO_POOL_SECRET_ID");
	}
	@Override
	public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {


		if ("OPTIONS".equals(input.getHttpMethod())) {
			return new APIGatewayProxyResponseEvent()
					.withStatusCode(200)
					.withHeaders(Map.of(
							"Access-Control-Allow-Origin", "http://localhost:5173",
							"Access-Control-Allow-Headers", "Content-Type,X-Amz-Date,Authorization,X-Api-Key,X-Amz-Security-Token,X-Requested-With",
							"Access-Control-Allow-Methods", "POST,OPTIONS",
							"Access-Control-Max-Age", "3600"
					));
		}

		APIGatewayProxyResponseEvent response= new APIGatewayProxyResponseEvent()
				.withHeaders(CORS_HEADERS);
		LambdaLogger logger= context.getLogger();

		try {
			JsonObject loginRequest= JsonParser.parseString(input.getBody()).getAsJsonObject();
			JsonObject loginResult=cognitoUserService.userLogin(loginRequest,appClientId,appClientSecret);
			response.withBody(new Gson().toJson(loginResult,JsonObject.class));
			response.withStatusCode(200);
		}catch (AwsServiceException ex){
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
