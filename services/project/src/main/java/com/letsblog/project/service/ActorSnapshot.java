package com.letsblog.project.service;

/**
 * リクエストの中で解決した操作者の写し(issue #1479)。{@code @Async}のジョブのスレッドには
 * {@code SecurityContextHolder}もリクエストも無い(media-serviceが画像生成ジョブで踏んだ
 * 「No thread-bound request found」、#1405)ので、受理側がリクエストスレッドで取り、
 * {@link CurrentActorService#runAs}でジョブのスレッドへ引き継ぐ。
 *
 * @param authorization 呼び出し元の{@code Authorization}ヘッダー値(ジョブ作成にだけ使う。
 *                      ジョブの更新はサービス自身のClient Credentialsで行う。#1083)
 */
public record ActorSnapshot(
        Long userId,
        String keycloakSub,
        String email,
        String remoteIp,
        String userAgent,
        String authorization,
        boolean admin) {

    /**
     * 利用者のBearerを持ち越さない写し(issue #1723)。ジョブのスレッドから呼ぶサービス間ブリッジは、
     * 5分で失効する利用者のトークンではなくサービス自身のClient Credentialsを使う。操作者・メール等は変えない。
     */
    public ActorSnapshot withoutAuthorization() {
        return new ActorSnapshot(userId, keycloakSub, email, remoteIp, userAgent, null, admin);
    }
}
