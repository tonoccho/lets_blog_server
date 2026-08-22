package com.letsblog.api.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration
public class TestcontainersConfiguration {

    @Bean
    public MySQLContainer<?> mysqlContainer() {
        var container = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
                .withDatabaseName("lets_blog_test")
                .withUsername("test_user")
                .withPassword("test_pass")
                .withReuse(true);

        container.start();
        return container;
    }
}
