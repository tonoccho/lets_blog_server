package com.letsblog.platform.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * BackupProperties(issue #694)をBean登録する。KeycloakAdminClientConfigと同じ
 * @EnableConfigurationProperties方式(services/platform/.../keycloak/KeycloakAdminClientConfig参照)。
 */
@Configuration
@EnableConfigurationProperties(BackupProperties.class)
public class BackupConfig {
}
