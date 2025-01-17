package com.roczyno.aws.task_manager.util;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;

import java.util.Collections;
import java.util.Map;

public class AuthorizationUtil {
	@SuppressWarnings("unchecked")
	public static Map<String, String> getUserClaims(APIGatewayProxyRequestEvent event) {
		if (event.getRequestContext() == null
				|| event.getRequestContext().getAuthorizer() == null
				|| event.getRequestContext().getAuthorizer().get("claims") == null) {
			return Collections.emptyMap();
		}

		Object claims = event.getRequestContext().getAuthorizer().get("claims");
		if (claims instanceof Map) {
			return (Map<String, String>) claims;
		}
		return Collections.emptyMap();
	}

	public static boolean isAdmin(APIGatewayProxyRequestEvent event) {
		Map<String, String> claims = getUserClaims(event);
		String cognitoGroups = claims.get("cognito:groups");
		return cognitoGroups != null && cognitoGroups.contains("ADMIN");
	}

	public static String getUserId(APIGatewayProxyRequestEvent event) {
		Map<String, String> claims = getUserClaims(event);
		return claims.get("custom:userId");
	}

	public static APIGatewayProxyResponseEvent forbidden() {
		return new APIGatewayProxyResponseEvent()
				.withStatusCode(403)
				.withBody("{\"message\": \"Forbidden: Insufficient permissions\"}");
	}
}
