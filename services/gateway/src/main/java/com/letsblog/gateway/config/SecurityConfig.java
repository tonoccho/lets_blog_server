package com.letsblog.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * gatewayのJWT検証設定(#560)。gateway自体は各下流サービスへの単純なリバースプロキシで
 * あり、宣言的な認可(@PreAuthorize)へのフル移行・deny-by-defaultへの転換は認可マトリクス
 * 整備(#568、B10)のスコープであるため、ここでは引き続き「Bearerトークンが無い場合は
 * 素通しし、実際の認証・認可判定は下流サービス(legacy-apiはissue #566でKeycloak JWT必須化
 * 済み、他サービスは#568で対応予定)に委ねる」に留める。
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
