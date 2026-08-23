package com.letsblog.logwriter.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * KeycloakのJWT検証設定(#572)。ログ読み取り・記録APIをlegacy-apiから移設するにあたって
 * log-writerに初めて追加するREST APIサーフェス向けのセキュリティ設定。
 *
 * <p>identity-serviceのSecurityConfig(#563)をテンプレートにしている。認可判定そのもの
 * (「本人か」「adminか」)はSpring Securityのauthorityではなく、各コントローラーが
 * {@link com.letsblog.logwriter.service.CurrentActorService}/
 * {@link com.letsblog.logwriter.service.AdminAuthorizationService}経由でJWTのsubクレームを
 * 読み、必要に応じてidentity-serviceの/api/identity/meへ問い合わせて手続き的に行う
 * (legacy-api/identity-serviceの既存パターンを踏襲)。
 *
 * <p>oauth2ResourceServer().jwt()を設定した時点で、Bearerトークンが実際に送られてきた場合は
 * Spring Securityの標準動作により無条件に検証される(permitAllのパスであっても、
 * Authorizationヘッダにトークンが付いていれば解決・検証を試み、不正/期限切れ/署名不正で
 * あれば401を返す)。本サービスの読み取りAPIは全て認証必須だが、実際の「未認証なら403」判定は
 * 各コントローラー側(CurrentActorService経由)で行っており、ここでは一律permitAllとしたうえで
 * JWTが提示された場合の検証のみを担う(legacy-api/identity-serviceと同じ二段構え)。
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
