package com.letsblog.identity.dto;

/**
 * publishing-service向けの内部ブリッジリクエスト(issue #707、#575設計判断4の読み取り側)。
 * PostPublishService#resolveAuthorIdのメール検索フォールバックが見つけた著者IDを
 * user_site_authorsへキャッシュ書き込みするために使う。
 */
public record CacheUserSiteAuthorBridgeRequest(Long userId, Long siteId, String cmsAuthorId) {
}
