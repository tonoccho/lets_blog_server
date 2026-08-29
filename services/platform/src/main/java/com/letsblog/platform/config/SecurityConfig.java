package com.letsblog.platform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * KeycloakのJWT検証設定。
 *
 * <p>本サービスが所有するエンドポイント(VscodeExtensionController・BackupController・
 * SystemSettingController・AppSettingController・DashboardController)は、いずれも元は
 * legacy-apiにあり、legacy-apiの{@code SecurityConfig}(公開パスを除き
 * {@code anyRequest().authenticated()})によって「有効なKeycloak JWTが無ければ401」という
 * 認証ゲートが掛かっていた。#693/#694/#695/#696でこれらをplatform-serviceへ移設した際、
 * 本クラスが他サービスのテンプレート通り全経路{@code permitAll()}だったため、その認証ゲートが
 * 失われ、{@code GET /api/system/vscode-extension}等がAuthorizationヘッダー無しでも200を返す
 * 後退が発生していた(issue #705。gateway側も{@code anyExchange().permitAll()}のため、
 * どちらの層でも認証必須化が行われていなかった)。
 *
 * <p>そこでlegacy-apiの{@code SecurityConfig}と同じ形(明示的な公開パスを除き
 * {@code anyRequest().authenticated()})へ戻し、移設前と同等の最低限のゲートを復元する。
 * gatewayを一律deny-by-defaultにする案も検討したが、gatewayは全サービス共通の経路であり
 * legacy-apiの公開パス({@code /api/auth/signup}等)や他サービスの現行モデルまで巻き込むため、
 * 所有サービス自身のSecurityConfigで閉じる方式(project-service/publishing-serviceが
 * {@code /api/internal/**}に対して既に採っている方式)を選択した。
 *
 * <p>{@link #PUBLIC_PATHS}として残すのは、(1) ヘルスチェック(Actuator。docker-composeの
 * healthcheckとgatewayの{@code DownstreamHealthConfig}が無認証で叩く)、(2) APIドキュメント、
 * (3) サービス間内部ブリッジ{@code /api/internal/platform/**}
 * ({@link com.letsblog.platform.controller.InternalPlatformSettingsController})のみ。
 * 内部ブリッジはgatewayのルート表に載っておらず外部から到達できない一方、唯一の呼び出し元である
 * legacy-apiの{@code PlatformServiceClient}がBearerトークンを転送しない実装のため、ここを
 * authenticatedにすると実行時に壊れる。移設前も同じ値はlegacy-apiのプロセス内で解決されており
 * 外部到達性は無かったため、本Issue(未認証で外部から到達できてしまう後退の解消)の範囲では
 * permitAllのまま据え置く。
 *
 * <p>ロールベースの認可(admin限定操作など)は引き続きコントローラ/サービス層から呼ばれる
 * AdminAuthorizationService/CurrentActorService(identity-service経由)が担う。「認証済みなら
 * 誰でも到達できる」エンドポイントが残っていること自体は{@code docs/AUTHORIZATION_MATRIX.md}の
 * 「既知のギャップ」であり、本Issueのスコープ外。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String[] PUBLIC_PATHS = {
            "/actuator/**",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/api/internal/platform/**"
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
