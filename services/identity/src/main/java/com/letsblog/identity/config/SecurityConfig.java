package com.letsblog.identity.config;

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
 * <p>#563の時点では{@code anyRequest().permitAll()}とし、「Bearerトークンが送られてきた場合は
 * 検証する」までに留めていた。認可判定自体は各コントローラから呼ばれる
 * {@code AdminAuthorizationService}/{@code PermissionAuthorizationService}
 * (CurrentActorServiceが解決するJWTのsubクレーム起点のactorId)が手続き的に行っており、
 * #566でCurrentActorServiceの旧ヘッダベースのフォールバックを撤去した後は、JWTが無ければ
 * 「操作者なし」として権限系のチェックが自動的に拒否されるためである。
 *
 * <p>しかしこれは「未認証でも到達はでき、コントローラ内のチェック有無に結果が依存する」状態であり、
 * legacy-api時代の{@code anyRequest().authenticated()}によるゲートからの後退だった
 * (issue #705で他サービスの同型の後退として発見、issue #772で是正)。ADR-0008のとおり
 * SecurityConfigレベルで一律に認証を必須化する。
 *
 * <p>{@link #PUBLIC_PATHS}はヘルスチェック(Actuator。docker-composeのhealthcheckとgatewayの
 * {@code DownstreamHealthConfig}が無認証で叩く)とAPIドキュメントのみ。他サービスは
 * {@code GET /api/identity/me}を共通の{@code IdentityClient}経由で呼ぶが、各サービスの
 * {@code CurrentActorService}は呼び出し元のAuthorizationヘッダーが無い場合そもそも呼び出しを
 * 行わない(「操作者なし」を返す)ため、authenticatedにしてもサービス間呼び出しは壊れない
 * (docs/SYNC_SERVICE_CALLS.md参照)。
 *
 * <p>realm roleのSpring Security authorityへのマッピング(ROLE_&lt;大文字&gt;)はここで用意しておくが、
 * CurrentActorServiceでの実際の権限判定は(#562時点でKeycloak側へのロール同期が未実装のため)
 * JWTのクレームではなくローカルDBのRole/Permissionを正とする。「認証済みなら誰でも到達できる」
 * エンドポイントが残っていること自体は{@code docs/AUTHORIZATION_MATRIX.md}の「既知のギャップ」であり、
 * 本Issueのスコープ外。
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
