package com.roczyno.aws.task_manager.authorizer;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.auth0.jwt.interfaces.DecodedJWT;

import java.util.Arrays;

/**
 * Handler for requests to Lambda function.
 */
public class LambdaAuthorizer implements RequestHandler<APIGatewayProxyRequestEvent, AuthorizerOutput> {

    @Override
    public AuthorizerOutput handleRequest(final APIGatewayProxyRequestEvent input, final Context context) {
        String effect = "Allow";
        String userName;

        // Get JWT from Authorization header
        String jwt = input.getHeaders().get("Authorization");
        if (jwt == null || jwt.isEmpty()) {
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
