package com.roczyno.aws.task_manager.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ExpiredTaskStepFunctionInput {
	private String taskId;
	private String assignedUserId;
	private String adminUserId;
	private String taskName;
	private LocalDateTime deadline;
}
