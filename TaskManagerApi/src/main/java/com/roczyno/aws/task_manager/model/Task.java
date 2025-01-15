package com.roczyno.aws.task_manager.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbConvertedBy;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;

import java.time.LocalDateTime;


@Data
@DynamoDbBean
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Task {
	private String id;
	private String name;
	private String description;
	private Status status;
	private LocalDateTime deadline;
	private LocalDateTime completedAt;
	private String userComment;
	private String assignedUserId;

	@DynamoDbPartitionKey
	@DynamoDbAttribute("id")
	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	@DynamoDbAttribute("name")
	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	@DynamoDbAttribute("description")
	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	@DynamoDbAttribute("status")
	@DynamoDbConvertedBy(StatusConverter.class)
	@DynamoDbSecondaryPartitionKey(indexNames = "StatusDeadlineIndex")
	public Status getStatus() {
		return status;
	}

	public void setStatus(Status status) {
		this.status = status;
	}

	@DynamoDbAttribute("deadline")
	@DynamoDbConvertedBy(LocalDateTimeConverter.class)
	@DynamoDbSecondarySortKey(indexNames = "StatusDeadlineIndex")
	public LocalDateTime getDeadline() {
		return deadline;
	}

	public void setDeadline(LocalDateTime deadline) {
		this.deadline = deadline;
	}

	@DynamoDbAttribute("completedAt")
	@DynamoDbConvertedBy(LocalDateTimeConverter.class)
	public LocalDateTime getCompletedAt() {
		return completedAt;
	}

	public void setCompletedAt(LocalDateTime completedAt) {
		this.completedAt = completedAt;
	}

	@DynamoDbAttribute("userComment")
	public String getUserComment() {
		return userComment;
	}

	public void setUserComment(String userComment) {
		this.userComment = userComment;
	}

	@DynamoDbAttribute("assignedUserId")
	@DynamoDbSecondaryPartitionKey(indexNames = "AssignedUserIdIndex")
	public String getAssignedUserId() {
		return assignedUserId;
	}

	public void setAssignedUserId(String assignedUserId) {
		this.assignedUserId = assignedUserId;
	}
}
