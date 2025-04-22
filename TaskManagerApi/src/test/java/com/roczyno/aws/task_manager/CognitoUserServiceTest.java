package com.roczyno.aws.task_manager;

import com.google.gson.JsonObject;
import com.roczyno.aws.task_manager.model.Role;
import com.roczyno.aws.task_manager.model.User;
import com.roczyno.aws.task_manager.service.CognitoUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.*;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.StartExecutionRequest;
import software.amazon.awssdk.services.sfn.model.StartExecutionResponse;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CognitoUserServiceTest {

	@Mock
	private CognitoIdentityProviderClient cognitoClient;

	@Mock
	private SfnClient sfnClient;

	private CognitoUserService cognitoUserService;

	private static final String USER_POOL_ID = "test-pool-id";
	private static final String APP_CLIENT_ID = "test-client-id";
	private static final String APP_CLIENT_SECRET = "test-client-secret";

	@BeforeEach
	void setUp() {
		MockitoAnnotations.openMocks(this);
		cognitoUserService = new CognitoUserService("us-east-1", cognitoClient);

		// Set environment variables for testing
		System.setProperty("AWS_REGION", "us-east-1");
		System.setProperty("ASSIGNMENT_TOPIC_ARN", "test-assignment-arn");
		System.setProperty("DEADLINE_TOPIC_ARN", "test-deadline-arn");
		System.setProperty("CLOSED_TOPIC_ARN", "test-closed-arn");
		System.setProperty("REOPENED_TOPIC_ARN", "test-reopened-arn");
		System.setProperty("COMPLETE_TOPIC_ARN", "test-complete-arn");
		System.setProperty("USER_ONBOARDING_STATE_MACHINE_ARN", "test-state-machine-arn");
	}

	@Test
	void userLogin_Success() {
		// Prepare test data
		JsonObject loginDetails = new JsonObject();
		loginDetails.addProperty("email", "test@example.com");
		loginDetails.addProperty("password", "password123");

		// Mock AWS responses
		AuthenticationResultType authResult = AuthenticationResultType.builder()
				.accessToken("test-access-token")
				.idToken("test-id-token")
				.refreshToken("test-refresh-token")
				.build();

		InitiateAuthResponse authResponse = (InitiateAuthResponse) InitiateAuthResponse.builder()
				.authenticationResult(authResult)
				.sdkHttpResponse(SdkHttpResponse.builder().statusCode(200).build())
				.build();

		List<AttributeType> userAttributes = new ArrayList<>();
		userAttributes.add(AttributeType.builder().name("email").value("test@example.com").build());
		userAttributes.add(AttributeType.builder().name("name").value("Test User").build());

		GetUserResponse getUserResponse = GetUserResponse.builder()
				.userAttributes(userAttributes)
				.build();

		when(cognitoClient.initiateAuth(any(InitiateAuthRequest.class))).thenReturn(authResponse);
		when(cognitoClient.getUser(any(GetUserRequest.class))).thenReturn(getUserResponse);

		// Execute test
		JsonObject result = cognitoUserService.userLogin(loginDetails, APP_CLIENT_ID, APP_CLIENT_SECRET);

		// Verify results
		assertTrue(result.get("isSuccessful").getAsBoolean());
		assertEquals(200, result.get("statusCode").getAsInt());
		assertEquals("test-access-token", result.get("accessToken").getAsString());
		assertEquals("test-id-token", result.get("idToken").getAsString());
		assertEquals("test-refresh-token", result.get("refreshToken").getAsString());
	}

	@Test
	void adminAddUser_Success() {
		// Prepare test data
		User user = new User();
		user.setEmail("test@example.com");
		user.setFirstName("Test");
		user.setLastName("User");
		user.setRole(Role.USER);

		// Mock AWS responses
		AdminCreateUserResponse createUserResponse = (AdminCreateUserResponse) AdminCreateUserResponse.builder()
				.sdkHttpResponse(SdkHttpResponse.builder().statusCode(200).build())
				.build();

		AdminAddUserToGroupResponse groupResponse = (AdminAddUserToGroupResponse) AdminAddUserToGroupResponse.builder()
				.sdkHttpResponse(SdkHttpResponse.builder().statusCode(200).build())
				.build();

		StartExecutionResponse sfnResponse = StartExecutionResponse.builder()
				.executionArn("test-execution-arn")
				.build();

		when(cognitoClient.adminCreateUser(any(AdminCreateUserRequest.class))).thenReturn(createUserResponse);
		when(cognitoClient.adminSetUserPassword(any(AdminSetUserPasswordRequest.class))).thenReturn(null);
		when(cognitoClient.adminAddUserToGroup(any(AdminAddUserToGroupRequest.class))).thenReturn(groupResponse);
		when(sfnClient.startExecution(any(StartExecutionRequest.class))).thenReturn(sfnResponse);

		// Execute test
		JsonObject result = cognitoUserService.adminAddUser(user, USER_POOL_ID);

		// Verify results
		assertTrue(result.get("isSuccessful").getAsBoolean());
		assertEquals(200, result.get("statusCode").getAsInt());
	}

	@Test
	void getAllUsers_Success() {
		// Prepare test data
		List<AttributeType> attributes = new ArrayList<>();
		attributes.add(AttributeType.builder().name("email").value("test@example.com").build());
		attributes.add(AttributeType.builder().name("custom:role").value("USER").build());
		attributes.add(AttributeType.builder().name("name").value("Test User").build());

		UserType user = UserType.builder()
				.username("test@example.com")
				.enabled(true)
				.userStatus("CONFIRMED")
				.userCreateDate(Instant.now())
				.attributes(attributes)
				.build();

		ListUsersResponse listUsersResponse = (ListUsersResponse) ListUsersResponse.builder()
				.users(List.of(user))
				.sdkHttpResponse(SdkHttpResponse.builder().statusCode(200).build())
				.build();

		when(cognitoClient.listUsers(any(ListUsersRequest.class))).thenReturn(listUsersResponse);

		// Execute test
		JsonObject result = cognitoUserService.getAllUsers(USER_POOL_ID);

		// Verify results
		assertTrue(result.get("isSuccessful").getAsBoolean());
		assertEquals(200, result.get("statusCode").getAsInt());
		assertNotNull(result.get("users"));
		assertTrue(result.get("users").getAsJsonArray().size() > 0);
	}

	@Test
	void getAllUsers_Error() {
		// Mock error response
		when(cognitoClient.listUsers(any(ListUsersRequest.class)))
				.thenThrow(new RuntimeException("Test error"));

		// Execute test
		JsonObject result = cognitoUserService.getAllUsers(USER_POOL_ID);

		// Verify results
		assertFalse(result.get("isSuccessful").getAsBoolean());
		assertNotNull(result.get("error"));
		assertEquals("Test error", result.get("error").getAsString());
	}
}
