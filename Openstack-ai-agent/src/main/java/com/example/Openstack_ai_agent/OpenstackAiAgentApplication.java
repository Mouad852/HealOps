package com.example.Openstack_ai_agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class OpenstackAiAgentApplication {

	public static void main(String[] args) {
		SpringApplication.run(OpenstackAiAgentApplication.class, args);
	}

}
