package com.letsblog.project.dto;

import com.letsblog.project.cms.CmsType;
import com.letsblog.project.service.SiteService;
import java.util.Map;

/**
 * サイトのCMS接続情報(issue #577受入基準: サイト認証情報の取得APIをpublishing-serviceから
 * 利用できるようにする)。credentialsはsshKeyPairId参照を実際の秘密鍵PEMへ解決済みの、
 * すぐ使える状態のMapを返す(project-service内部でSiteService/ProvisioningServiceが
 * CmsProvisioningBridgeClientへ渡すのと同じ表現)。
 */
public record SiteCredentialsResponse(Long siteId, CmsType cmsType, Map<String, String> credentials) {

    public static SiteCredentialsResponse from(SiteService.ResolvedSiteCredentials resolved) {
        return new SiteCredentialsResponse(resolved.siteId(), resolved.cmsType(), resolved.credentials());
    }
}
