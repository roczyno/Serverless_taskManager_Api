package com.roczyno.aws.task_manager;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.SubscribeRequest;
import software.amazon.awssdk.services.sns.model.SubscribeResponse;

import java.util.*;

public class SNSSubscriptionHandler implements RequestHandler<Map<String, String>, Map<String, Object>> {
	private final SnsClient snsClient;

	public SNSSubscriptionHandler() {
		this.snsClient = SnsClient.builder()
				.region(Region.of(System.getenv("AWS_REGION")))
				.build();
	}

	public Map<String, Object> handleRequest(Map<String, String> input, Context context) {
		LambdaLogger logger = context.getLogger();
		Map<String, Object> result = new HashMap<>();

		String email = input.get("email");
		String topicArn = input.get("topicArn");
		String userId = input.get("userId");
		String role = input.get("role");

		if (email == null || email.isEmpty() || userId == null || userId.isEmpty()) {
			logger.log("ERROR: Required parameters missing");
			result.put("error", "Missing required parameters");
			result.put("success", false);
			return result;
		}

		try {
			// Create the filter policy
			Map<String, Object> filterPolicy = new HashMap<>();

			// For admin users - no filtering, receive all notifications
			if ("ADMIN".equals(role)) {
				// Create a filter policy that matches everything
				Map<String, String> matchAllCondition = new HashMap<>();
				matchAllCondition.put("anything-but", "non-existing-value");
				filterPolicy.put("messageType", Collections.singletonList(matchAllCondition));
			} else {
				// Regular user filter policy - only receive notifications for assigned tasks
				filterPolicy.put("assignedUserId", Collections.singletonList(userId));
			}

			String filterPolicyJson = new ObjectMapper().writeValueAsString(filterPolicy);

			// Create the subscription with filter policy
			SubscribeRequest request = SubscribeRequest.builder()
					.protocol("email")
					.endpoint(email)
					.topicArn(topicArn)
					.returnSubscriptionArn(true)
					.attributes(Collections.singletonMap("FilterPolicy", filterPolicyJson))
					.build();

			SubscribeResponse response = snsClient.subscribe(request);

			result.put("subscriptionArn", response.subscriptionArn());
			result.put("success", true);
			result.put("email", email);
			result.put("topicArn", topicArn);
			result.put("userId", userId);
			result.put("filterPolicy", filterPolicyJson);

		} catch (Exception e) {
			logger.log("ERROR: " + e.getMessage());
			result.put("error", e.getMessage());
			result.put("success", false);
		}

		return result;
	}
}
