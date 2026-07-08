package com.yash.Notifyr;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class NotifyrApplication {

	public static void main(String[] args) {
		SpringApplication.run(NotifyrApplication.class, args);
	}

}
