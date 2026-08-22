package com.letsblog.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * gatewayのJWT検証設定(#560)。認証基盤の移行はまだ完了していない
 * (legacy-apiは引き続きX-API-Keyで認証し、Web/VSCode拡張もまだKeycloakトークンを
 * 送っていない。#563/#564/#565が実施されるまでの間)ため、Bearerトークンが
 * 無い場合は素通しし、旧認証方式を今まで通り機能させる。
 *
 * <p>ただし oauth2ResourceServer().jwt() を設定した時点で、Bearerトークンが
 * 実際に送られてきた場合は無条件に検証される(Spring Securityの標準動作:
 * permitAllのパスであっても、Authorizationヘッダにトークンが付いていれば
 * 解決・検証を試み、不正/期限切れ/署名不正であれば401を返す)。これにより
 * 「無効なJWTは拒否するが、トークン無しのリクエストは(旧方式のまま)通す」
 * という受入基準を満たす。
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchanges -> exchanges.anyExchange().permitAll())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> { }))
                .build();
    }
}
