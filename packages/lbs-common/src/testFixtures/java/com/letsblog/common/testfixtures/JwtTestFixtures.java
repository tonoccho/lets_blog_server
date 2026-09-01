package com.letsblog.common.testfixtures;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Keycloak発行JWTを模したテスト用{@link Jwt}を組み立てる共通フィクスチャ(#587)。
 *
 * <p>各サービスのテストが、KeycloakのJWT形状(realmロールは{@code realm_access.roles}
 * クレーム、サービス間通信のトークンは{@code azp}クレームのみでend-userのsub無し)を
 * 個別に手組みしていた重複を解消するために{@code packages/lbs-common}のテストフィクスチャ
 * ({@code java-test-fixtures}プラグイン)として提供する。利用側は
 * {@code testImplementation testFixtures(project(':packages:lbs-common'))}を追加すればよい。
 *
 * <p>ロール→authority変換規則(大文字化+{@code ROLE_}プレフィックス)は、
 * legacy-api/identity-serviceの{@code KeycloakRealmRoleConverter}と同じ規則を
 * {@link #jwtRequestPostProcessor}側でも再現している。ただしこのフィクスチャ自体は
 * 各サービスのConverter実装には依存しない(lbs-commonはドメインロジックを持たない
 * 方針(#554)のため)。
 */
public final class JwtTestFixtures {

    private static final String DEFAULT_TOKEN_VALUE = "test-token";
    private static final long DEFAULT_TTL_SECONDS = 60;

    private JwtTestFixtures() {
    }

    /**
     * 指定したsubjectとrealmロールを持つ、エンドユーザーのJWTを組み立てる。
     *
     * <p>{@link org.springframework.security.core.context.SecurityContextHolder}へ
     * 手動で{@link org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken}
     * を設定する単体テスト向け(例: {@code CurrentActorServiceTest}のスタイル)。
     *
     * @param subject     JWTの{@code sub}クレーム
     * @param realmRoles  {@code realm_access.roles}クレームに含めるロール名
     *                    (例: "admin"。{@code ROLE_}プレフィックス・大文字化は付与前の生の値)
     */
    public static Jwt jwt(String subject, String... realmRoles) {
        Instant now = Instant.now();
        return Jwt.withTokenValue(DEFAULT_TOKEN_VALUE)
                .header("alg", "RS256")
                .subject(subject)
                .claim("sub", subject)
                .claim("realm_access", Map.of("roles", List.of(realmRoles)))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(DEFAULT_TTL_SECONDS))
                .build();
    }

    /**
     * {@link #jwt(String, String...)}と同じ内容のJWTを、{@code @SpringBootTest}+MockMvcの
     * 統合テストでリクエストへ付与できる{@link RequestPostProcessor}として返す。
     *
     * <p>authorityは、legacy-api/identity-serviceの{@code KeycloakRealmRoleConverter}と
     * 同じ規則(ロール名を大文字化し{@code ROLE_}を前置)で{@code realmRoles}から算出して
     * 設定する。{@code mockMvc.perform(get(...).with(JwtTestFixtures.jwtRequestPostProcessor(...)))}
     * のように使う。
     *
     * @param subject     JWTの{@code sub}クレーム
     * @param realmRoles  {@code realm_access.roles}クレームおよび付与authorityの元になるロール名
     */
    public static RequestPostProcessor jwtRequestPostProcessor(String subject, String... realmRoles) {
        Jwt jwt = jwt(subject, realmRoles);
        List<GrantedAuthority> authorities = Arrays.stream(realmRoles)
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.toUpperCase(Locale.ROOT)))
                .map(GrantedAuthority.class::cast)
                .toList();
        return SecurityMockMvcRequestPostProcessors.jwt().jwt(jwt).authorities(authorities);
    }

    /**
     * サービス間通信(ADR-0005)を将来検証する際に使う、サービス用トークンを模したJWTを
     * 組み立てる。{@code sub}クレームを持たず、呼び出し元サービスのクライアントIDを
     * {@code azp}クレームへ設定する点でエンドユーザーのJWTと区別される。
     *
     * <p>2026-08時点(#587)では、このJWTを受理する実装(サービストークン認証)自体が
     * まだ存在しないため、このメソッドを消費するテストはまだ無い。ADR-0005・Phase 19の
     * サービス間呼び出しが実装された際に、下流サービスの認証テストから利用することを
     * 想定した先行提供。
     *
     * @param clientId 呼び出し元サービスのクライアントID(例: "letsblog-services")
     */
    public static Jwt serviceJwt(String clientId) {
        Instant now = Instant.now();
        return Jwt.withTokenValue("test-service-token")
                .header("alg", "RS256")
                .claim("azp", clientId)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(DEFAULT_TTL_SECONDS))
                .build();
    }
}
