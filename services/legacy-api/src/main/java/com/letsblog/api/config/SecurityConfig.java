package com.letsblog.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * KeycloakのJWT検証設定(#563)。
 *
 * <p>本サービスの認可は現時点でも{@link ApiKeyAuthFilter}(X-API-Key)と、各サービスクラスから
 * 手続き的に呼ばれる{@code AdminAuthorizationService}/{@code PermissionAuthorizationService}
 * (X-Actor-Id/X-Actor-Role。Web BFFがNextAuthセッションを転送する前提のモデル)が担っている。
 * Web/VSCode拡張はまだKeycloakトークンを送っていない(#564/#565が未着手)ため、この設定は
 * 「Bearerトークンが送られてきた場合は検証する」までに留め、認可判定そのものは既存の仕組みを
 * 変更しない。トークン無しのリクエストは(既存のApiKeyAuthFilter等はそのまま機能する前提で)
 * 素通しする。
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
 *
 * <p>全エンドポイントを対象にした宣言的認可(@PreAuthorize)へのフル移行、
 * X-Actor-*ヘッダを無視する既定拒否化は、Web(#564)・VSCode拡張(#565)の
 * Keycloakトークン送信対応、およびカットオーバー手順の確定(#591)より前に行うと
 * 唯一の実ユーザーアカウントを含む全クライアントを即座にログアウトさせてしまうため、
 * 本Issueでは実施しない(詳細はPRの説明を参照)。
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
