package com.letsblog.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.letsblog.api", "com.letsblog.common"})
@EnableScheduling
@EnableAsync
public class LetsBlogApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(LetsBlogApiApplication.class, args);
    }
}
