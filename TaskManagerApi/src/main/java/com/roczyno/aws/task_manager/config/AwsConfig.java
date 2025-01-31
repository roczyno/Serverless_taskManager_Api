package com.roczyno.aws.task_manager.config;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;

@Configuration
public class AwsConfig {
	private static final String awsRegion = System.getenv("AWS_REGION");



	public static CognitoIdentityProviderClient cognitoIdentityProviderClient(){
		return CognitoIdentityProviderClient.builder()
				.region(Region.of(awsRegion))
				.build();
	}

	@Bean
	public static SesClient sesClient() {
		return SesClient.builder()
				.credentialsProvider(DefaultCredentialsProvider.create())
				.region(Region.of(awsRegion))
				.build();
	}

	@Bean
	public static SqsClient sqsClient() {
		return SqsClient.builder()
				.credentialsProvider(DefaultCredentialsProvider.create())
				.region(Region.of(awsRegion))
				.build();
	}

	@Bean
	public static SnsClient snsClient() {
		return SnsClient.builder()
				.credentialsProvider(DefaultCredentialsProvider.create())
				.region(Region.of(awsRegion))
				.build();
	}

	@Bean
	public static DynamoDbClient dynamoDbClient() {
		return DynamoDbClient.builder()
				.credentialsProvider(DefaultCredentialsProvider.create())
				.region(Region.of(awsRegion))
				.build();
	}

	public static SfnClient sfnClient(){
		return SfnClient.builder()
				.region(Region.of(awsRegion))
				.build();
	}



	@Bean
	public static ObjectMapper objectMapper() {
		ObjectMapper mapper = new ObjectMapper();
		mapper.registerModule(new JavaTimeModule());
		mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
		return mapper;
	}
}
