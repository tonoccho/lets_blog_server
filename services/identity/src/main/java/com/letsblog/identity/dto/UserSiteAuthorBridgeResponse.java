package com.letsblog.identity.dto;

/**
 * publishing-service向けの内部ブリッジ応答(issue #707、#575設計判断4の読み取り側)。
 * user_site_authorsの対応表照会結果。
 */
public record UserSiteAuthorBridgeResponse(String cmsAuthorId) {
}
