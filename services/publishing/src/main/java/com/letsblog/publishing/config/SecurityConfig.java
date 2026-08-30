package com.letsblog.publishing.config;

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
 * <p>#707/#708/#709/#712でlegacy-apiから移設した時点では、project-serviceのテンプレートに倣って
 * {@code /api/internal/**}のみ{@code authenticated()}とし、残りは{@code anyRequest().permitAll()}の
 * ままだった。Javadocにも「gatewayが実際のエンドユーザートラフィックの検証を担う想定」と
 * 書かれていたが、gatewayは{@code anyExchange().permitAll()}であり認証ゲートを担っていない
 * (ADR-0008)。結果として{@code /api/posts/publish}・{@code /api/taxonomy/resolve}・
 * {@code /api/projects/{projectId}/bulk-management/**}等が未認証で到達できる後退が残っていた
 * (issue #705で同型の後退として発見、issue #772で是正)。
 *
 * <p>{@link #PUBLIC_PATHS}はヘルスチェック(Actuator。docker-composeのhealthcheckとgatewayの
 * {@code DownstreamHealthConfig}が無認証で叩く)とAPIドキュメントのみ。{@code /api/internal/publishing/**}・
 * {@code /api/internal/ai/**}・{@code /api/internal/project/cms/**}は従来どおり認証必須で、
 * {@code anyRequest().authenticated()}に含まれる(個別の
 * {@code requestMatchers("/api/internal/**").authenticated()}は同じ結果になるため不要になった)。
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
