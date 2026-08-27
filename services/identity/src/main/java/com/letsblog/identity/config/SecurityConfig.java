package com.letsblog.identity.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * KeycloakのJWT検証設定(#563)。
 *
 * <p>本サービスの認可は各サービスクラスから手続き的に呼ばれる
 * {@code AdminAuthorizationService}/{@code PermissionAuthorizationService}
 * (CurrentActorServiceが解決するKeycloak JWTのsubクレーム起点のactorId)が担っている。
 * issue #566でCurrentActorServiceの旧ヘッダベースのフォールバックは撤去済みで、
 * JWTが無ければ「操作者なし」を返す(admin/権限系のチェックは自動的に拒否される)。
 * ただし、そもそも上記チェックを呼ばないエンドポイント全体を対象にした宣言的認可
 * (@PreAuthorize)へのフル移行・deny-by-defaultへの転換は、認可マトリクス整備(#568、B10)の
 * スコープであり本サービスでは未実施のため、この設定は引き続き「Bearerトークンが送られてきた
 * 場合は検証する」までに留める。
 *
 * <p>oauth2ResourceServer().jwt()を設定した時点で、Bearerトークンが実際に送られてきた場合は
 * Spring Securityの標準動作により無条件に検証される(permitAllのパスであっても、
 * Authorizationヘッダにトークンが付いていれば解決・検証を試み、不正/期限切れ/署名不正で
 * あれば401を返す)。これにより「gatewayを経由せず直接サービスを叩いた場合もJWT検証が働く」
 * という受入基準を、実際に認可をJWTへ全面移行することなく満たす。
 *
 * <p>realm roleのSpring Security authorityへのマッピング(ROLE_&lt;大文字&gt;)はここで
 * 用意しておくが、CurrentActorServiceでの実際の権限判定は(#562時点でKeycloak側への
 * ロール同期が未実装のため)JWTのクレームではなくローカルDBのRole/Permissionを正とする。
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
