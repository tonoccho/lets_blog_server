package com.letsblog.publishing.dto;

/** CMS側に作成/更新された著者(ユーザー)IDを返す(issue #707、#575設計判断4の書き込み側)。 */
public record AuthorProvisioningBridgeResponse(String cmsAuthorId) {
}
