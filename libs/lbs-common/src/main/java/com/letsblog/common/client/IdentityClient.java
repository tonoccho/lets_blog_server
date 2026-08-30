package com.letsblog.common.client;

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
}
