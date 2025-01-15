package com.roczyno.aws.task_manager.model;

import lombok.Data;

@Data
public class CreateUserRequest {
	private String firstName;
	private String lastName;
	private String email;
	private Role role;

}
