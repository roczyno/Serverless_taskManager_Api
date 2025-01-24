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
import software.amazon.awssdk.services.cognitoidentityprovider.model.ConfirmSignUpRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ConfirmSignUpResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.DeliveryMediumType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.EmailConfigurationType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.GetUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.GetUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InitiateAuthRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InitiateAuthResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ListUsersRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ListUsersResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.MessageActionType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.SignUpRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.SignUpResponse;
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
	private final NotificationService notificationService;

	public CognitoUserService(String region, CognitoIdentityProviderClient cognitoIdentityProviderClient, NotificationService notificationService){
		this.cognitoIdentityProviderClient = cognitoIdentityProviderClient;
		this.notificationService = notificationService;
	}


	public JsonObject createUser(JsonObject user,String appClientId,String appClientSecret){

		String email=user.get("email").getAsString();
		String password=user.get("password").getAsString();
		String firstName=user.get("firstName").getAsString();
		String lastName=user.get("lastName").getAsString();
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

		List<AttributeType> attributes=new ArrayList<>();
		attributes.add(nameAttribute);
		attributes.add(emailAttribute);
		attributes.add(attributeUserId);



		String generateSecretHash=calculateSecretHash(appClientId,appClientSecret,email);
		SignUpRequest signUpRequest= SignUpRequest.builder()
				.username(email)
				.password(password)
				.userAttributes(attributes)
				.clientId(appClientId)
				.secretHash(generateSecretHash)
				.build();

		SignUpResponse signUpResponse=cognitoIdentityProviderClient.signUp(signUpRequest);
		JsonObject createUserResult= new JsonObject();
		createUserResult.addProperty("isSuccessful",signUpResponse.sdkHttpResponse().isSuccessful());
		createUserResult.addProperty("statusCode",signUpResponse.sdkHttpResponse().statusCode());
		createUserResult.addProperty("cognitoUserId",signUpResponse.userSub());
		createUserResult.addProperty("isConfirmed",signUpResponse.userConfirmed());

		return createUserResult;
	}

	public static String calculateSecretHash(String userPoolClientId, String userPoolClientSecret, String userName) {
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

	public JsonObject confirmUserSignUp(String appClientId,String appClientSecret,String email,String confirmationCode){
		String generateSecretHash=calculateSecretHash(appClientId,appClientSecret,email);
	ConfirmSignUpRequest confirmSignUpRequest=ConfirmSignUpRequest.builder()
				.secretHash(generateSecretHash)
				.username(email)
				.confirmationCode(confirmationCode)
				.clientId(appClientId)
				.build();

	ConfirmSignUpResponse confirmSignUpResponse=cognitoIdentityProviderClient.confirmSignUp(confirmSignUpRequest);
	JsonObject confirmUserResponse=new JsonObject();
	confirmUserResponse.addProperty("isSuccessful",confirmSignUpResponse.sdkHttpResponse().isSuccessful());
	confirmUserResponse.addProperty("statusCode",confirmSignUpResponse.sdkHttpResponse().statusCode());
	return confirmUserResponse;
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

		// Get user details using the access token
		GetUserRequest getUserRequest = GetUserRequest.builder()
				.accessToken(authenticationResultType.accessToken())
				.build();
		GetUserResponse getUserResponse = cognitoIdentityProviderClient.getUser(getUserRequest);

		// Create user details object
		JsonObject userDetails = new JsonObject();
		getUserResponse.userAttributes().forEach(attribute -> {
			userDetails.addProperty(attribute.name(), attribute.value());
		});

		// Add user details to response
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

	public JsonObject getUser(String accessToken){
		GetUserRequest getUserRequest=GetUserRequest.builder()
				.accessToken(accessToken)
				.build();
		GetUserResponse getUserResponse= cognitoIdentityProviderClient.getUser(getUserRequest);
		JsonObject getUserResult=new JsonObject();
		getUserResult.addProperty("isSuccessful",getUserResponse.sdkHttpResponse().isSuccessful());
		getUserResult.addProperty("statusCode",getUserResponse.sdkHttpResponse().statusCode());
		List<AttributeType> userAttributes= getUserResponse.userAttributes();
		JsonObject userDetails=new JsonObject();
		userAttributes.stream().forEach((u)->{
			userDetails.addProperty(u.name(),u.value());
		});
		getUserResult.add("user",userDetails);
		return getUserResult;
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
			// Create input for Step Function with proper structure
			ObjectMapper objectMapper = new ObjectMapper();
			ObjectNode stepFunctionInput = objectMapper.createObjectNode();

			// Add core fields
			stepFunctionInput.put("email", email);
			stepFunctionInput.put("role", role.toString());
			stepFunctionInput.put("userId",userId);

			// Add topic ARNs
			String assignmentTopicArn = System.getenv("ASSIGNMENT_TOPIC_ARN");
			String deadlineTopicArn = System.getenv("DEADLINE_TOPIC_ARN");
			String closedTopicArn = System.getenv("CLOSED_TOPIC_ARN");
			String reopenedTopicArn = System.getenv("REOPENED_TOPIC_ARN");
			String completeTopicArn = System.getenv("COMPLETE_TOPIC_ARN");

			// Verify all ARNs are present
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

			// Log the input for debugging
			System.out.println("Step Function Input: " + stepFunctionInput.toString());

			// Create Step Functions client
			SfnClient stepFunctionsClient = SfnClient.builder()
					.region(Region.of(System.getenv("AWS_REGION")))
					.build();

			String stepFunctionInputJson = stepFunctionInput.toString();
			System.out.println("Debug - Step Function Input JSON: " + stepFunctionInputJson);
			// Start execution with proper JSON string
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
			// Create request to list users
			ListUsersRequest listUsersRequest = ListUsersRequest.builder()
					.userPoolId(userPoolId)
					.build();

			// Get response from Cognito
			ListUsersResponse listUsersResponse = cognitoIdentityProviderClient.listUsers(listUsersRequest);

			// Create response object
			JsonObject getAllUsersResult = new JsonObject();
			JsonArray users = new JsonArray();

			// Process each user
			for (UserType user : listUsersResponse.users()) {
				JsonObject userDetails = new JsonObject();

				// Add basic user properties
				userDetails.addProperty("username", user.username());
				userDetails.addProperty("enabled", user.enabled());
				userDetails.addProperty("userStatus", user.userStatusAsString());
				userDetails.addProperty("userCreateDate", user.userCreateDate().toString());

				// Process user attributes
				JsonObject attributes = new JsonObject();
				String userRole = null;
				String name = null;

				for (AttributeType attribute : user.attributes()) {
					attributes.addProperty(attribute.name(), attribute.value());

					// Extract role from custom attribute
					if (attribute.name().equals("custom:role")) {
						userRole = attribute.value();
						userDetails.addProperty("role", userRole);
					}

					// Extract name attribute
					if (attribute.name().equals("name")) {
						name = attribute.value();
						userDetails.addProperty("name", name);
					}
				}

				userDetails.add("attributes", attributes);

				// If role wasn't found in custom attributes, set as null or default
				if (userRole == null) {
					userDetails.addProperty("role", "UNDEFINED");
				}

				// If name wasn't found, set as null or empty string
				if (name == null) {
					userDetails.addProperty("name", "");
				}

				users.add(userDetails);
			}

			// Add users array to result
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
