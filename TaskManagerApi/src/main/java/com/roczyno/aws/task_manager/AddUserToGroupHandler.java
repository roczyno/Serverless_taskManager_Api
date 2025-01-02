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
import com.roczyno.aws.task_manager.service.CognitoUserService;
import software.amazon.awssdk.awscore.exception.AwsServiceException;

import java.util.HashMap;
import java.util.Map;

public class AddUserToGroupHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
	private final CognitoUserService cognitoUserService;
	private final String userPoolId;
	public AddUserToGroupHandler(){
		this.cognitoUserService=new CognitoUserService(System.getenv("AWS_REGION"));
		this.userPoolId=System.getenv("TM_COGNITO_USER_POOL_ID");

	}
	@Override
	public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {

		Map<String,String> headers= new HashMap<>();
		headers.put("Content-Type","application/json");

		APIGatewayProxyResponseEvent response= new APIGatewayProxyResponseEvent()
				.withHeaders(headers);

		LambdaLogger logger= context.getLogger();

		try {
			JsonObject requestBody= JsonParser.parseString(input.getBody()).getAsJsonObject();
			String groupName=requestBody.get("groupName").getAsString();
			String userName=input.getPathParameters().get("userName");
			JsonObject addUserToGroupResult=cognitoUserService.addUserToGroup(groupName,userName,userPoolId);
			response.withBody(new Gson().toJson(addUserToGroupResult,JsonObject.class));
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
