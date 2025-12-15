package com.newspulse.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Main application class for NewsPulse API
 */
@SpringBootApplication
@EnableCaching
@EnableScheduling
public class NewsPulseApplication {

    public static void main(String[] args) {
        SpringApplication.run(NewsPulseApplication.class, args);
    }
}
