package com.roczyno.aws.task_manager.authorizer;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.google.gson.Gson;

import java.util.Arrays;
import java.util.Map;

/**
 * Handler for requests to Lambda function.
 */
public class LambdaAuthorizer implements RequestHandler<APIGatewayProxyRequestEvent, AuthorizerOutput> {

    @Override
    public AuthorizerOutput handleRequest(final APIGatewayProxyRequestEvent input, final Context context) {
        String effect = "Allow";
        String userName;
        LambdaLogger logger = context.getLogger();

        // Log the entire input event
        logger.log("Input event: " + new Gson().toJson(input));

        // Check if input is null
        if (input == null) {
            logger.log("Input event is null");
            return createAuthorizerOutput("unauthorized", "Deny", input);
        }

        // Log the headers specifically
        Map<String, String> headers = input.getHeaders();
        logger.log("Headers: " + (headers != null ? new Gson().toJson(headers) : "null"));

        // If headers are null, check the raw request context
        if (headers == null) {
            logger.log("Headers are null, checking raw request context");
            if (input.getRequestContext() != null) {
                logger.log("Request context: " + new Gson().toJson(input.getRequestContext()));
            } else {
                logger.log("Request context is also null");
            }
            return createAuthorizerOutput("unauthorized", "Deny", input);
        }

        // Get Authorization header - try both cases
        String authHeader = headers.get("Authorization");
        if (authHeader == null) {
            authHeader = headers.get("authorization");
        }

        if (authHeader == null || authHeader.isEmpty()) {
            logger.log("No Authorization header found");
            return createAuthorizerOutput("unauthorized", "Deny", input);
        }

        logger.log("Found Authorization header: " + authHeader.substring(0, Math.min(authHeader.length(), 20)) + "...");

        // Extract JWT token from "Bearer <jwt_token>"
        String jwt;
        if (authHeader.startsWith("Bearer ")) {
            jwt = authHeader.substring(7); // Skip the "Bearer " prefix
        } else {
            logger.log("Invalid Authorization header format");
            return createAuthorizerOutput("unauthorized", "Deny", input);
        }

        // Get configuration from environment
        String region = System.getenv("AWS_REGION");
        String userPoolId = System.getenv("TM_COGNITO_USER_POOL_ID");
        String audience = System.getenv("TM_COGNITO_POOL_CLIENT_ID");

        // Validate JWT and extract username
        JwtUtil jwtUtils = new JwtUtil();
        try {
            DecodedJWT decodedJWT = jwtUtils.validateJwt(jwt, region, userPoolId, audience);
            userName = decodedJWT.getSubject();

            // Log the token type (for debugging)
            context.getLogger().log("Token use: " + decodedJWT.getClaim("token_use").asString());

        } catch (RuntimeException ex) {
            context.getLogger().log("JWT validation failed: " + ex.getMessage());
            return createAuthorizerOutput("unauthorized", "Deny", input);
        }

        return createAuthorizerOutput(userName, effect, input);
    }

    private AuthorizerOutput createAuthorizerOutput(String principalId, String effect, APIGatewayProxyRequestEvent input) {
        APIGatewayProxyRequestEvent.ProxyRequestContext proxyRequestContext = input.getRequestContext();

        String arn = String.format("arn:aws:execute-api:%s:%s:%s/%s/%s/%s",
                System.getenv("AWS_REGION"),
                proxyRequestContext.getAccountId(),
                proxyRequestContext.getApiId(),
                proxyRequestContext.getStage(),
                proxyRequestContext.getHttpMethod(),
                "*");

        Statement statement = Statement.builder()
                .action("execute-api:Invoke")
                .effect(effect)
                .resource(arn)
                .build();

        PolicyDocument policyDocument = PolicyDocument.builder()
                .version("2012-10-17")
                .statements(Arrays.asList(statement))
                .build();

        return AuthorizerOutput.builder()
                .principalId(principalId)
                .policyDocument(policyDocument)
                .build();
    }
}
