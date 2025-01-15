package com.roczyno.aws.task_manager.model;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class ExpiredTaskInput {
	private String taskId;
	private String taskName;
	private String assignedUserId;
	private String adminUserId;
	private LocalDateTime deadline;
}
