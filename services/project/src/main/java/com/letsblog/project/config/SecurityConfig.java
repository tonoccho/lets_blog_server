package com.letsblog.project.config;

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
 * <p>#577でlegacy-apiから移設した時点では、{@code /api/internal/**}(サイトのCMS認証情報等、
 * センシティブな内容を返すサービス間専用ブリッジ)のみ{@code authenticated()}とし、残りは
 * {@code anyRequest().permitAll()}のままだった。Javadocにも「gatewayが実際のエンドユーザー
 * トラフィックの検証を担う想定」と書かれていたが、gatewayは{@code anyExchange().permitAll()}で
 * あり認証ゲートを担っていない(ADR-0008)。結果として{@code GET /api/projects}が未認証で
 * プロジェクト名・スラッグ等の実データを返す状態になっていた(issue #772で発見・是正)。
 *
 * <p>{@link #PUBLIC_PATHS}はヘルスチェック(Actuator。docker-composeのhealthcheckとgatewayの
 * {@code DownstreamHealthConfig}が無認証で叩く)とAPIドキュメントのみ。{@code /api/internal/project/**}は
 * 従来どおり認証必須で、{@code anyRequest().authenticated()}に含まれる(個別の
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
