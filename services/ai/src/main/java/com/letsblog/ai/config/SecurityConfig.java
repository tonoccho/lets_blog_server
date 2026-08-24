package com.letsblog.ai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * KeycloakのJWT検証設定。identity-service/log-writer/media-serviceのSecurityConfigをテンプレートに
 * している(#563/#572/#573)。認可判定そのものは、まだ簡易なもの(全経路permitAll、JWTが提示されて
 * いればその検証のみ行う)に留める。legacy-apiのApiKeyAuthFilterに相当する仕組みはまだ持たないため、
 * ここを経由するリクエストは現時点では未認証でも通す(gatewayが実際のエンドユーザートラフィックの
 * 検証を担う想定。legacy-api/log-writer/media-serviceと同じ二段構え)。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .oauth2ResourceServer(
                        oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
                .build();
    }

    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoleConverter());
        return converter;
    }
}
