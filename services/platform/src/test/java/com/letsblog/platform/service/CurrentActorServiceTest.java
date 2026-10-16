package com.letsblog.platform.service;

import com.letsblog.platform.testsupport.PlatformWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link CurrentActorService}(issue #816の認証判定、issue #1825の分岐の網羅)。
 *
 * <p>#816以前は{@code getCurrentActorKeycloakSub() != null}、つまり<b>JWTのsubクレームだけ</b>を
 * 見ており、identity-serviceへの問い合わせを伴わなかった。無効化しても発行済みトークンは
 * 失効しない(Keycloakが止めるのは新規発行だけ)ため、<b>無効化されたユーザーが素通りしていた</b>。
 * #816で操作者の解決可否({@code getCurrentActorId() != null})による判定へ変えた。
 *
 * <p>identity-serviceはHTTPのサーバー側フェイクで模し、{@code IdentityClient}・
 * {@code CurrentActorService}は実物を通す。
 */
@DisplayName("platform CurrentActorService")
class CurrentActorServiceTest {

    private PlatformWorld world;
    private CurrentActorService service;

    @BeforeEach
    void setUp() {
        world = new PlatformWorld();
        service = world.currentActorService;
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    @Test
    @DisplayName("有効なユーザーは認証済みと判定する")
    void 有効なユーザー() {
        world.actingAsUser(1L);

        assertThat(service.isAuthenticated()).isTrue();
        assertThat(service.getCurrentActorId()).isEqualTo(1L);
        assertThat(service.isAdmin()).isFalse();
    }

    @Test
    @DisplayName("adminはisAdminがtrue")
    void admin() {
        world.actingAsAdmin(2L);

        assertThat(service.isAdmin()).isTrue();
    }

    /**
     * identity-serviceが無効化ユーザーに対して401/403を返す状態を再現する。#829で401/403は
     * 「操作者なし」へ畳まれるので、例外ではなくfalseが正解(identity-serviceの障害ではない)。
     */
    @Test
    @DisplayName("無効化ユーザー(identityが401)は認証済みと判定しない")
    void 無効化ユーザー() {
        world.actingAsUser(3L);
        world.http.status("GET", "/api/identity/me", 403, "{\"error\":\"disabled\"}");

        assertThat(service.isAuthenticated()).isFalse();
        assertThat(service.getCurrentActorId()).isNull();
    }

    /**
     * 無効化(401/403)と違い、identity-serviceの障害は「未認証」へ握り潰さず伝播させる。
     * 静かにfalseへ縮退させると、認証チェックが素通りする方向の不具合を生みかねないため。
     */
    @Test
    @DisplayName("identity-serviceの障害は未認証へ縮退させず伝播させる")
    void identity障害() {
        world.actingAsUser(4L).identityServiceDown();

        assertThatThrownBy(() -> service.isAuthenticated())
                .isInstanceOf(IdentityServiceUnavailableException.class);
    }

    @Test
    @DisplayName("tryGetCurrentActorIdはidentity-serviceの障害をnullへ握りつぶす")
    void try取得は障害をnullにする() {
        world.actingAsUser(4L).identityServiceDown();

        assertThat(service.tryGetCurrentActorId()).isNull();
    }

    @Test
    @DisplayName("tryGetCurrentActorIdは正常時に操作者IDを返す")
    void try取得は正常時にIDを返す() {
        world.actingAsUser(5L);

        assertThat(service.tryGetCurrentActorId()).isEqualTo(5L);
    }

    @Test
    @DisplayName("Authorizationヘッダーが無ければ認証済みと判定せず、identity-serviceへ問い合わせない")
    void ヘッダーなし() {
        world.anonymous();

        assertThat(service.isAuthenticated()).isFalse();
        assertThat(world.http.requests()).isEmpty();
    }

    @Test
    @DisplayName("空白だけのAuthorizationヘッダーも未認証として扱う")
    void 空白ヘッダー() {
        world.anonymous().request.addHeader("Authorization", "  ");

        assertThat(service.isAuthenticated()).isFalse();
        assertThat(world.http.requests()).isEmpty();
    }

    @Test
    @DisplayName("プロフィールは1リクエストにつき1回だけidentity-serviceへ問い合わせ、結果をリクエストにキャッシュする")
    void プロフィールはリクエスト内でキャッシュされる() {
        world.actingAsAdmin(6L);

        service.isAdmin();
        service.getCurrentActorId();
        service.isAuthenticated();

        assertThat(world.http.requests("GET", "/api/identity/me")).hasSize(1);
    }

    @Test
    @DisplayName("JWTが無ければ、subは取れない")
    void jwtなしはsubがnull() {
        world.signedInAs(null);

        assertThat(service.getCurrentActorKeycloakSub()).isNull();
    }

    @Test
    @DisplayName("JWTのsubをそのまま返す")
    void jwtのsub() {
        world.signedInAs("kc-sub-1");

        assertThat(service.getCurrentActorKeycloakSub()).isEqualTo("kc-sub-1");
    }

    @Test
    @DisplayName("subクレームが空なら、subは取れない")
    void 空のsubはnull() {
        world.signedInAs(" ");

        assertThat(service.getCurrentActorKeycloakSub()).isNull();
    }

    @Test
    @DisplayName("subクレームが無いJWTなら、subは取れない")
    void subクレームなしはnull() {
        org.springframework.security.oauth2.jwt.Jwt jwt = org.springframework.security.oauth2.jwt.Jwt
                .withTokenValue("t").header("alg", "none").claim("scope", "x").build();
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(jwt));

        assertThat(service.getCurrentActorKeycloakSub()).isNull();
    }

    @Test
    @DisplayName("X-Forwarded-Forがあればそれを、無ければ接続元アドレスを返す")
    void リモートIP() {
        world.request.setRemoteAddr("10.0.0.9");
        assertThat(service.getRemoteIp()).isEqualTo("10.0.0.9");

        world.request.addHeader("X-Forwarded-For", "  ");
        assertThat(service.getRemoteIp()).isEqualTo("10.0.0.9");

        world.request.removeHeader("X-Forwarded-For");
        world.request.addHeader("X-Forwarded-For", "203.0.113.5");
        assertThat(service.getRemoteIp()).isEqualTo("203.0.113.5");
    }

    @Test
    @DisplayName("User-AgentとAuthorizationヘッダーをそのまま返す")
    void ヘッダーの取得() {
        world.request.addHeader("User-Agent", "JUnit");
        world.actingAsUser(7L);

        assertThat(service.getUserAgent()).isEqualTo("JUnit");
        assertThat(service.getAuthorizationHeader()).isEqualTo("Bearer token-7");
    }
}
