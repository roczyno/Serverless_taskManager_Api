package com.roczyno.aws.task_manager;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.roczyno.aws.task_manager.service.CognitoUserService;
import software.amazon.awssdk.awscore.exception.AwsServiceException;

import java.util.HashMap;
import java.util.Map;

public class GetUserHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
	private final CognitoUserService cognitoUserService;

	public GetUserHandler(){
		this.cognitoUserService=new CognitoUserService(System.getenv("AWS_REGION"));

	}
	@Override
	public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
		Map<String,String> headers= new HashMap<>();
		headers.put("Content-Type","application/json");

		APIGatewayProxyResponseEvent response= new APIGatewayProxyResponseEvent()
				.withHeaders(headers);

		LambdaLogger logger= context.getLogger();
		try {
			Map<String,String> requestHeaders= input.getHeaders();
			JsonObject userDetails=cognitoUserService.getUser(requestHeaders.get("accessToken"));
			response.withBody(new Gson().toJson(userDetails,JsonObject.class));
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
