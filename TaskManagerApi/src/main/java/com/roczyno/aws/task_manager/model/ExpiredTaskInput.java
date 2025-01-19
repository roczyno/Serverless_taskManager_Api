package com.roczyno.aws.task_manager.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ExpiredTaskInput {
	private String taskId;
	private String taskName;
	private String assignedUserId;
	private String deadline;
	private String snsTopicArn;
}
