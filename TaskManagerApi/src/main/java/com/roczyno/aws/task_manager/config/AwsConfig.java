package com.roczyno.aws.task_manager.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;

@Configuration
public class AwsConfig {
	private static final String awsRegion = System.getenv("AWS_REGION");

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
		return new ObjectMapper();
	}
}