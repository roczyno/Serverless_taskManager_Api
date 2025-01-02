package com.roczyno.aws.task_manager.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class User {
	private String username;
	private String email;
	private String role;
	private boolean enabled;
}
