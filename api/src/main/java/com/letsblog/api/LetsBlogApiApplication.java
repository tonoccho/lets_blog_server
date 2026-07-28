package com.letsblog.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class LetsBlogApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(LetsBlogApiApplication.class, args);
    }
}
