package com.letsblog.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * gatewayのJWT検証設定(#560)。gateway自体は各下流サービスへの単純なリバースプロキシで
 * あり、<strong>「有効なKeycloak JWTが無ければ401」という認証ゲートは担わない</strong>。
 * 認証ゲートは各サービス自身の{@code SecurityConfig}(明示的な公開パスを除き
 * {@code anyRequest().authenticated()})が担う、というのがプロジェクトの決定である
 * ({@code docs/adr/0008-auth-gate-in-each-service-security-config.md}、および
 * {@code docs/AUTHORIZATION_MATRIX.md}の「認証ゲートの実施レイヤー」節を参照)。
 * したがってここは{@code anyExchange().permitAll()}のままとし、Bearerトークンが無いことを
 * 理由にgatewayがリクエストを拒否することはない。
 *
 * <p>ただし oauth2ResourceServer().jwt() を設定した時点で、Bearerトークンが
 * 実際に送られてきた場合は無条件に検証される(Spring Securityの標準動作:
 * permitAllのパスであっても、Authorizationヘッダにトークンが付いていれば
 * 解決・検証を試み、不正/期限切れ/署名不正であれば401を返す)。これにより
 * 「無効なJWTは拒否するが、トークン無しのリクエストは下流の判定に委ねる」
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
