package com.letsblog.analytics.config;

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
 * <p>#578でlegacy-apiから移設した時点では、他の抽出サービスと同じテンプレート
 * ({@code anyRequest().permitAll()})のままで、Javadocにも「gatewayが実際のエンドユーザー
 * トラフィックの検証を担う想定」と書かれていた。しかしgatewayは{@code anyExchange().permitAll()}で
 * あり認証ゲートを担っていない(ADR-0008)。結果としてどちらの層でも認証必須化が行われず、
 * legacy-api時代には401だった{@code /api/projects/{projectId}/dashboard/**}(GA/AdSenseレポート)がAuthorizationヘッダー無しで到達できる後退が
 * 残っていた(issue #705で発見、issue #772で本サービスを含む8サービスを是正)。
 *
 * <p>{@link #PUBLIC_PATHS}はヘルスチェック(Actuator。docker-composeのhealthcheckとgatewayの
 * {@code DownstreamHealthConfig}が無認証で叩く)とAPIドキュメントのみ。サービス間内部ブリッジ
 * {@code /api/internal/analytics/**}は公開しない。唯一の呼び出し元であるlegacy-apiの
 * {@code AnalyticsProjectSettingsClient}は{@code ServiceAuthHeaders.forwardedBearer}で
 * 呼び出し元ユーザーのBearerトークンを転送するため、authenticatedのままで到達性は保たれる
 * (docs/SYNC_SERVICE_CALLS.md参照)。
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
