package com.letsblog.publishing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code @EnableScheduling}は、{@link com.letsblog.publishing.cms.ssh.SshjCommandExecutor}の
 * 遊休SSH接続の切断(@Scheduled)を動かすために必要(issue #1725。これが無いと@Scheduledは黙って登録されない)。
 */
@SpringBootApplication
@EnableScheduling
public class PublishingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PublishingServiceApplication.class, args);
    }
}
