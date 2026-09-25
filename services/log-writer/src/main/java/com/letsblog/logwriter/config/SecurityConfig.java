package com.letsblog.logwriter.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * KeycloakのJWT検証設定。
 *
 * <p>本サービスの認証ゲート(「有効なKeycloak JWTが無ければ401」)は、
 * {@code docs/adr/0008-auth-gate-in-each-service-security-config.md}(ADR-0008)のとおり
 * このクラスが担う。{@link #PUBLIC_PATHS}に列挙したパスだけを{@code permitAll()}にし、
 * それ以外は{@code anyRequest().authenticated()}とする(サービス単位のdeny-by-default)。
 *
 * <p>#572でlegacy-apiから移設した時点では{@code anyRequest().permitAll()}とし、「未認証なら403」の
 * 判定は各コントローラー側({@code CurrentActorService}経由)に任せていた。しかしgatewayは
 * {@code anyExchange().permitAll()}であり認証ゲートを担っていない(ADR-0008)ため、legacy-api時代には
 * {@code anyRequest().authenticated()}で401だった{@code /api/logs/**}・{@code /api/audit-logs/**}・
 * {@code /api/operation-logs/**}が、未認証で到達できる後退が残っていた
 * (issue #705で同型の後退として発見、issue #772で是正)。
 *
 * <p>{@link #PUBLIC_PATHS}はヘルスチェック(Actuator。docker-composeのhealthcheckとgatewayの
 * {@code DownstreamHealthConfig}が無認証で叩く)とAPIドキュメントのみ。本サービスは
 * {@code /api/internal/**}配下の内部ブリッジを持たない。
 *
 * <p>ロールベースの認可(admin限定操作、プロジェクトメンバー判定)は引き続きコントローラ/サービス層から
 * 呼ばれる{@code AdminAuthorizationService}/{@code CurrentActorService}(identity-service経由)が担う。
 * 「認証済みなら誰でも到達できる」エンドポイントが残っていること自体は
 * {@code docs/AUTHORIZATION_MATRIX.md}の「既知のギャップ」であり、本Issueのスコープ外。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String[] PUBLIC_PATHS = {
            "/actuator/**",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html"
    };

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated())
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
