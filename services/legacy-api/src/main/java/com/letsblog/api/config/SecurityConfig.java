package com.letsblog.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * KeycloakのJWT検証設定。
 *
 * <p>issue #566で{@code ApiKeyAuthFilter}(旧ヘッダベースのAPIキー認証)を撤去したのに伴い、このサービスの
 * 認証ゲート(「有効な資格情報が無ければ401」)はここへ一本化した。旧{@code ApiKeyAuthFilter}が
 * {@code /api/health}と一部の認証系公開パスを除く全{@code /api/**}を対象にしていたのと同じ範囲を、
 * {@code anyRequest().authenticated()}(有効なKeycloak JWTを要求)で置き換える。
 *
 * <p>permitAllとして残すのは、(1) まだ資格情報を持ちようがない初回セットアップ導線
 * ({@code /api/auth/setup} / {@code /api/auth/setup-status}。
 * AuthControllerのJavadoc参照)、(2) ヘルスチェック({@code /api/health}、Actuator)、
 * (3) 元々ApiKeyAuthFilterの対象外だったAPIドキュメント({@code /v3/api-docs/**}、
 * {@code /swagger-ui/**}、{@code /swagger-ui.html})のみ。認可判定自体(admin/プロジェクト
 * メンバー等)は引き続きコントローラから呼ばれる{@code AdminAuthorizationService}/
 * {@code PermissionAuthorizationService}(CurrentActorServiceが解決するJWTのsubクレーム起点)が
 * 担う。
 *
 * <p>realm roleのSpring Security authorityへのマッピング(ROLE_&lt;大文字&gt;)はここで
 * 用意しておくが、CurrentActorServiceでの実際の権限判定は(#562時点でKeycloak側への
 * ロール同期が未実装のため)JWTのクレームではなくローカルDBのRole/Permissionを正とする。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String[] PUBLIC_PATHS = {
            "/api/health",
            "/api/auth/setup",
            "/api/auth/setup-status",
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
