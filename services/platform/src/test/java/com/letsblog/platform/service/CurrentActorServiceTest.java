package com.letsblog.platform.service;

import com.letsblog.common.client.ActorProfile;
import com.letsblog.common.client.IdentityClient;
import com.letsblog.common.client.SyncServiceClientErrorException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * {@link CurrentActorService#isAuthenticated()}の判定基準(issue #816)。
 *
 * <p>#816以前は{@code getCurrentActorKeycloakSub() != null}、つまり<b>JWTのsubクレームだけ</b>を
 * 見ており、identity-serviceへの問い合わせを伴わなかった。無効化しても発行済みトークンは
 * 失効しない(Keycloakが止めるのは新規発行だけ)ため、<b>無効化されたユーザーが素通りしていた</b>。
 *
 * <p>この判定は{@code AdminAuthorizationService#requireAuthenticated()}を経由して
 * {@code SystemSettingService#getBraveSearchApiKeyStatus()}が使う。「admin限定ではないが
 * ログインは必要」というエンドポイントの唯一の関門であり、そこが無効化を見ていなかった。
 *
 * <p>#816で操作者の解決可否({@code getCurrentActorId() != null})による判定へ変えた。
 * identity-serviceが無効化ユーザーを操作者として解決しなくなったため、これで無効化が反映される。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("platform CurrentActorService: 認証判定(issue #816)")
class CurrentActorServiceTest {

    @Mock
    private HttpServletRequest request;

    @Mock
    private IdentityClient identityClient;

    private CurrentActorService service() {
        return new CurrentActorService(request, identityClient);
    }

    @Test
    @DisplayName("有効なユーザーは認証済みと判定する")
    void 有効なユーザー() {
        when(request.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer token");
        when(identityClient.fetchProfile("Bearer token")).thenReturn(new ActorProfile(1L, "user"));

        assertThat(service().isAuthenticated()).isTrue();
    }

    /**
     * identity-serviceが無効化ユーザーに対して403を返す状態を再現する。
     * #816以前はJWTのsubしか見ていなかったため、この状況でもtrueを返していた。
     */
    @Test
    @DisplayName("無効化ユーザー(identityが403)は認証済みと判定しない")
    void 無効化ユーザー() {
        when(request.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer token");
        when(identityClient.fetchProfile("Bearer token")).thenThrow(
                new SyncServiceClientErrorException(
                        "identity-service", "GET /api/identity/me", 403, "Forbidden", null));

        // identity-serviceへの問い合わせ失敗は「未認証」へ握り潰さず伝播させる設計
        // (lookupProfileのJavadoc参照)。素通りしないことがここでの要点。
        assertThatThrownBy(() -> service().isAuthenticated())
                .isInstanceOf(IdentityServiceUnavailableException.class);
    }

    @Test
    @DisplayName("Authorizationヘッダーが無ければ認証済みと判定しない")
    void ヘッダーなし() {
        when(request.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn(null);

        assertThat(service().isAuthenticated()).isFalse();
    }
}
