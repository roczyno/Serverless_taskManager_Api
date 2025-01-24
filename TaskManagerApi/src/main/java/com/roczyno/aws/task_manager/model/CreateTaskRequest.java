package com.roczyno.aws.task_manager.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CreateTaskRequest {
	private String name;
	private String description;
	private Status status;
	private LocalDateTime deadline;
	private String userComment;
	private String assignedUserName;
	private String assignedUserId;
}
