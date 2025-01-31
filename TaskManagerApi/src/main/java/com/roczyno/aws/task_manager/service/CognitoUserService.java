package com.roczyno.aws.task_manager.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.roczyno.aws.task_manager.model.Role;
import com.roczyno.aws.task_manager.model.User;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminAddUserToGroupRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminAddUserToGroupResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminCreateUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminCreateUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminSetUserPasswordRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthFlowType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthenticationResultType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.DeliveryMediumType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.EmailConfigurationType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.GetUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.GetUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InitiateAuthRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InitiateAuthResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ListUsersRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ListUsersResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UpdateUserPoolRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserType;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.StartExecutionRequest;
import software.amazon.awssdk.services.sfn.model.StartExecutionResponse;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class CognitoUserService {
	private final CognitoIdentityProviderClient cognitoIdentityProviderClient;


	public CognitoUserService(String region, CognitoIdentityProviderClient cognitoIdentityProviderClient){
		this.cognitoIdentityProviderClient = cognitoIdentityProviderClient;

	}


	private static String calculateSecretHash(String userPoolClientId, String userPoolClientSecret, String userName) {
		final String HMAC_SHA256_ALGORITHM = "HmacSHA256";

		SecretKeySpec signingKey = new SecretKeySpec(
				userPoolClientSecret.getBytes(StandardCharsets.UTF_8),
				HMAC_SHA256_ALGORITHM);
		try {
			Mac mac = Mac.getInstance(HMAC_SHA256_ALGORITHM);
			mac.init(signingKey);
			mac.update(userName.getBytes(StandardCharsets.UTF_8));
			byte[] rawHmac = mac.doFinal(userPoolClientId.getBytes(StandardCharsets.UTF_8));
			return Base64.getEncoder().encodeToString(rawHmac);
		} catch (Exception e) {
			throw new RuntimeException("Error while calculating ");
		}
	}





	public JsonObject userLogin(JsonObject loginDetails, String appClientId, String appClientSecret) {
		String email = loginDetails.get("email").getAsString();
		String generateSecretHash = calculateSecretHash(appClientId, appClientSecret, email);
		String password = loginDetails.get("password").getAsString();
		Map<String, String> authParams = new HashMap<String, String>() {
			{
				put("USERNAME", email);
				put("PASSWORD", password);
				put("SECRET_HASH", generateSecretHash);
			}
		};
		InitiateAuthRequest authRequest = InitiateAuthRequest.builder()
				.clientId(appClientId)
				.authFlow(AuthFlowType.USER_PASSWORD_AUTH)
				.authParameters(authParams)
				.build();

		InitiateAuthResponse initiateAuthResponse = cognitoIdentityProviderClient.initiateAuth(authRequest);
		AuthenticationResultType authenticationResultType = initiateAuthResponse.authenticationResult();
		JsonObject loginUserResponse = new JsonObject();
		loginUserResponse.addProperty("isSuccessful", initiateAuthResponse.sdkHttpResponse().isSuccessful());
		loginUserResponse.addProperty("statusCode", initiateAuthResponse.sdkHttpResponse().statusCode());
		loginUserResponse.addProperty("idToken", authenticationResultType.idToken());
		loginUserResponse.addProperty("accessToken", authenticationResultType.accessToken());
		loginUserResponse.addProperty("refreshToken", authenticationResultType.refreshToken());


		GetUserRequest getUserRequest = GetUserRequest.builder()
				.accessToken(authenticationResultType.accessToken())
				.build();
		GetUserResponse getUserResponse = cognitoIdentityProviderClient.getUser(getUserRequest);


		JsonObject userDetails = new JsonObject();
		getUserResponse.userAttributes().forEach(attribute -> {
			userDetails.addProperty(attribute.name(), attribute.value());
		});


		loginUserResponse.add("user", userDetails);

		return loginUserResponse;
	}

	private void addUserToGroup(String groupName, String userName, String userPoolId){
	AdminAddUserToGroupRequest adminAddUserToGroupRequest=AdminAddUserToGroupRequest.builder()
				.groupName(groupName)
				.username(userName)
				.userPoolId(userPoolId)
				.build();
	AdminAddUserToGroupResponse adminAddUserToGroupResponse= cognitoIdentityProviderClient.adminAddUserToGroup(adminAddUserToGroupRequest);
		JsonObject addUserToGroupResponse=new JsonObject();
		addUserToGroupResponse.addProperty("isSuccessful",adminAddUserToGroupResponse.sdkHttpResponse().isSuccessful());
		addUserToGroupResponse.addProperty("statusCode",adminAddUserToGroupResponse.sdkHttpResponse().statusCode());

	}



	public JsonObject adminAddUser(User user, String userPoolId){

		String email=user.getEmail();
		String password=generateTempPassword();
		Role role=user.getRole();
		String firstName=user.getFirstName();
		String lastName=user.getLastName();
		String userId= UUID.randomUUID().toString();

		AttributeType attributeUserId=AttributeType.builder()
				.name("custom:userId")
				.value(userId)
				.build();
		AttributeType emailAttribute=AttributeType.builder()
				.name("email")
				.value(email)
				.build();
		AttributeType nameAttribute=AttributeType.builder()
				.name("name")
				.value(firstName+" "+lastName)
				.build();
		AttributeType attributeUserRole=AttributeType.builder()
				.name("custom:role")
				.value(String.valueOf(role))
				.build();
		AttributeType emailVerified=AttributeType.builder()
				.name("email_verified")
				.value("true")
				.build();

		List<AttributeType> attributes=new ArrayList<>();
		attributes.add(nameAttribute);
		attributes.add(emailAttribute);
		attributes.add(attributeUserId);
		attributes.add(attributeUserRole);
		attributes.add(emailVerified);

		AdminCreateUserRequest adminCreateUserRequest=AdminCreateUserRequest.builder()
				.userPoolId(userPoolId)
				.username(email)
				.desiredDeliveryMediums(DeliveryMediumType.EMAIL)
				.userAttributes(attributes)
				.temporaryPassword(password)
				.build();

		AdminCreateUserResponse adminCreateUserResponse=cognitoIdentityProviderClient.adminCreateUser(adminCreateUserRequest);

		AdminSetUserPasswordRequest passwordRequest = AdminSetUserPasswordRequest.builder()
				.userPoolId(userPoolId)
				.username(email)
				.password(password)
				.permanent(true)
				.build();

		cognitoIdentityProviderClient.adminSetUserPassword(passwordRequest);

		JsonObject addUserResponse=new JsonObject();
		addUserResponse.addProperty("isSuccessful",adminCreateUserResponse.sdkHttpResponse().isSuccessful());
		addUserResponse.addProperty("statusCode",adminCreateUserResponse.sdkHttpResponse().statusCode());

		addUserToGroup(String.valueOf(role),email,userPoolId);

		try {

			ObjectMapper objectMapper = new ObjectMapper();
			ObjectNode stepFunctionInput = objectMapper.createObjectNode();


			stepFunctionInput.put("email", email);
			stepFunctionInput.put("role", role.toString());
			stepFunctionInput.put("userId",userId);


			String assignmentTopicArn = System.getenv("ASSIGNMENT_TOPIC_ARN");
			String deadlineTopicArn = System.getenv("DEADLINE_TOPIC_ARN");
			String closedTopicArn = System.getenv("CLOSED_TOPIC_ARN");
			String reopenedTopicArn = System.getenv("REOPENED_TOPIC_ARN");
			String completeTopicArn = System.getenv("COMPLETE_TOPIC_ARN");


			if (assignmentTopicArn == null || deadlineTopicArn == null ||
					closedTopicArn == null || reopenedTopicArn == null ||
					completeTopicArn == null) {
				throw new IllegalStateException("One or more required SNS Topic ARNs are missing");
			}

			stepFunctionInput.put("assignmentTopicArn", assignmentTopicArn);
			stepFunctionInput.put("deadlineTopicArn", deadlineTopicArn);
			stepFunctionInput.put("closedTopicArn", closedTopicArn);
			stepFunctionInput.put("reopenedTopicArn", reopenedTopicArn);
			stepFunctionInput.put("completeTopicArn", completeTopicArn);




			SfnClient stepFunctionsClient = SfnClient.builder()
					.region(Region.of(System.getenv("AWS_REGION")))
					.build();


			StartExecutionRequest executionRequest = StartExecutionRequest.builder()
					.stateMachineArn(System.getenv("USER_ONBOARDING_STATE_MACHINE_ARN"))
					.input(stepFunctionInput.toString())
					.build();

			StartExecutionResponse response = stepFunctionsClient.startExecution(executionRequest);
			addUserResponse.addProperty("subscriptionInitiated", true);
			addUserResponse.addProperty("executionArn", response.executionArn());

		} catch (Exception e) {
			System.err.println("Error initiating SNS subscriptions: " + e.getMessage());
			e.printStackTrace();
			addUserResponse.addProperty("subscriptionInitiated", false);
			addUserResponse.addProperty("subscriptionError", e.getMessage());
		}

		return addUserResponse;
	}

	public void configureCognitoEmail(String userPoolId) {
		EmailConfigurationType emailConfig = EmailConfigurationType.builder()
				.emailSendingAccount("COGNITO_DEFAULT")
				.build();

		UpdateUserPoolRequest updateRequest = UpdateUserPoolRequest.builder()
				.userPoolId(userPoolId)
				.emailConfiguration(emailConfig)
				.build();

		cognitoIdentityProviderClient.updateUserPool(updateRequest);
	}


	private String generateTempPassword() {
		return UUID.randomUUID().toString().substring(0, 8) + "Aa1!";
	}

	public JsonObject getAllUsers(String userPoolId) {
		try {

			ListUsersRequest listUsersRequest = ListUsersRequest.builder()
					.userPoolId(userPoolId)
					.build();


			ListUsersResponse listUsersResponse = cognitoIdentityProviderClient.listUsers(listUsersRequest);


			JsonObject getAllUsersResult = new JsonObject();
			JsonArray users = new JsonArray();


			for (UserType user : listUsersResponse.users()) {
				JsonObject userDetails = new JsonObject();


				userDetails.addProperty("username", user.username());
				userDetails.addProperty("enabled", user.enabled());
				userDetails.addProperty("userStatus", user.userStatusAsString());
				userDetails.addProperty("userCreateDate", user.userCreateDate().toString());


				JsonObject attributes = new JsonObject();
				String userRole = null;
				String name = null;

				for (AttributeType attribute : user.attributes()) {
					attributes.addProperty(attribute.name(), attribute.value());


					if (attribute.name().equals("custom:role")) {
						userRole = attribute.value();
						userDetails.addProperty("role", userRole);
					}


					if (attribute.name().equals("name")) {
						name = attribute.value();
						userDetails.addProperty("name", name);
					}
				}

				userDetails.add("attributes", attributes);


				if (userRole == null) {
					userDetails.addProperty("role", "UNDEFINED");
				}


				if (name == null) {
					userDetails.addProperty("name", "");
				}

				users.add(userDetails);
			}


			getAllUsersResult.add("users", users);
			getAllUsersResult.addProperty("isSuccessful", listUsersResponse.sdkHttpResponse().isSuccessful());
			getAllUsersResult.addProperty("statusCode", listUsersResponse.sdkHttpResponse().statusCode());

			return getAllUsersResult;

		} catch (Exception e) {
			JsonObject errorResult = new JsonObject();
			errorResult.addProperty("isSuccessful", false);
			errorResult.addProperty("error", e.getMessage());
			return errorResult;
		}
	}
}
