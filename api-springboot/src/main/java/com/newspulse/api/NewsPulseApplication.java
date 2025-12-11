package com.newspulse.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

/**
 * Main application class for NewsPulse API
 */
@SpringBootApplication
@EnableCaching
public class NewsPulseApplication {

    public static void main(String[] args) {
        SpringApplication.run(NewsPulseApplication.class, args);
    }
}
