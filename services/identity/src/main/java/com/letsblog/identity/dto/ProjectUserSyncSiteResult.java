package com.letsblog.identity.dto;

/**
 * issue #1242: メンバー個別のユーザー情報再同期({@code ProjectUserSyncService#syncUserProfileToProjectSites}
 * の実行結果)における、サイト1件分の反映結果。
 *
 * <p>要件3(一部の環境への反映が失敗しても、成功した環境の結果は保持したまま失敗した環境と
 * その理由を返す)をそのまま表現する。
 */
public record ProjectUserSyncSiteResult(
        Long siteId,
        String siteKey,
        String siteName,
        boolean success,
        String errorMessage
) {
}
