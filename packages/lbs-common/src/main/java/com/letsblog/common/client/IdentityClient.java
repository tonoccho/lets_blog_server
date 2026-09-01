package com.letsblog.common.client;

import java.util.Optional;
import org.springframework.web.client.RestClient;

/**
 * identity-serviceの{@code GET /api/identity/me}を、呼び出し元のBearerトークンをそのまま転送して
 * 問い合わせる共通クライアント(issue #581、C12)。
 *
 * <p>ADR-0004によりusersテーブルへクロススキーマアクセスできない各サービス(lbs_content/lbs_media/
 * lbs_ai/lbs_analytics/lbs_logスキーマ)は、「自分自身のuserId」「admin権限を持つか」の解決を、
 * 常にidentity-serviceへの同期HTTP呼び出しに委ねる。log-writer(#572)/media-service(#573)/
 * ai-service(#574)/content-service(#576)/analytics-service(#578)がそれぞれ「C12が定まるまでの
 * 暫定策」として個別実装していたクライアント(リトライ・サーキットブレーカー無し)を、この
 * 共通実装へ置き換える。
 *
 * <p>方針: identity-serviceが応答しない場合は機能縮退(admin判定をfalse扱いにする等)せず、
 * {@link SyncServiceException}をそのまま呼び出し元(各サービスの{@code CurrentActorService})へ
 * 伝播させる。認可判定に使う情報のため、フォールバックで安全側に倒すつもりが誤って権限昇格に
 * ならないよう、"fail open"にはしない(docs/SYNC_SERVICE_CALLS.md参照)。
 */
public final class IdentityClient {

    private final SyncServiceClient client;

    public IdentityClient(RestClient.Builder builder, String identityServiceUri) {
        this.client = SyncServiceClient.builder(builder, "identity-service", identityServiceUri)
                .profile(SyncCallProfile.SHORT)
                .build();
    }

    /**
     * @param bearerToken {@code Authorization}ヘッダーの値をそのまま渡す(例: {@code "Bearer xxx"}）。
     * @throws SyncServiceException identity-serviceへの呼び出しが失敗した場合
     *         (タイムアウト・5xx・通信断・サーキットブレーカー作動中のいずれか)。
     */
    public ActorProfile fetchProfile(String bearerToken) {
        return client.get(
                "/api/identity/me", new Object[0], ActorProfile.class, ServiceAuthHeaders.forwardedBearer(bearerToken));
    }

    /**
     * 操作者を解決する。<b>認証・認可の結果</b>と<b>サービス障害</b>を区別する(issue #829)。
     *
     * <p>identity-serviceが401/403を返すのは「このトークンでは操作者を解決できない」という
     * <b>正常な判定結果</b>であって障害ではない(#816以降、無効化されたユーザーがこれに当たる)。
     * これを{@link SyncServiceException}のまま伝播させると呼び出し元が
     * {@code IdentityServiceUnavailableException}へ翻訳し、最終的に502になる。すると
     *
     * <ul>
     *   <li>無効化ユーザーがブラウザを開いたままにしているだけで、アクセストークンの寿命の間
     *       各サービスが502を返し続け、監視が誤爆する</li>
     *   <li>その502と、本当にidentity-serviceが落ちている502をログから区別できない</li>
     * </ul>
     *
     * <p>そこで401/403は{@link Optional#empty()}(操作者なし)として返す。呼び出し元の
     * {@code requireAdmin()}等がその先で403を返すため、拒否されること自体は変わらない
     * (fail-closedのまま)。
     *
     * <p><b>5xx・タイムアウト・通信断・サーキットブレーカー作動は従来どおり例外のまま</b>伝播させる。
     * ここを一緒に握り潰すと、identity-service障害時に「操作者なし」へ静かに縮退し、
     * 権限チェックが素通りする方向の不具合になりうる(このクラスのJavadoc冒頭の方針)。
     *
     * @throws SyncServiceException identity-serviceへの呼び出しが<b>障害として</b>失敗した場合
     */
    public Optional<ActorProfile> lookupProfile(String bearerToken) {
        try {
            return Optional.of(fetchProfile(bearerToken));
        } catch (SyncServiceClientErrorException e) {
            if (isAuthRejection(e.statusCode())) {
                return Optional.empty();
            }
            throw e;
        }
    }

    /** identity-serviceからの401/403は「操作者を解決できない」という判定結果で、障害ではない。 */
    private static boolean isAuthRejection(int statusCode) {
        return statusCode == 401 || statusCode == 403;
    }
}
