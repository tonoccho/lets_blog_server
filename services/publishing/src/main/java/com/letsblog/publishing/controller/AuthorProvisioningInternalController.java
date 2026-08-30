package com.letsblog.publishing.controller;

import com.letsblog.publishing.cms.AuthorProvisioningRequest;
import com.letsblog.publishing.dto.AuthorProvisioningBridgeRequest;
import com.letsblog.publishing.dto.AuthorProvisioningBridgeResponse;
import com.letsblog.publishing.service.AuthorProvisioningService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * legacy-api向けの内部ブリッジ(issue #707、#575設計判断4「著者マッピング双方向ブリッジ」の書き込み側)。
 * {@code user_site_authors}の所有権はlegacy-apiに残るため、{@code ProjectUserSyncService#provisionUserOnSite}
 * (プロジェクトメンバー追加/ロール変更時のWordPressユーザー自動プロビジョニング)は、CMS側の実際の
 * ユーザー作成/更新をこのブリッジ経由で本サービスへ依頼する。返ってきたcmsAuthorIdの
 * {@code user_site_authors}への永続化は引き続きlegacy-api側で行う。
 */
@RestController
public class AuthorProvisioningInternalController {

    private final AuthorProvisioningService authorProvisioningService;

    public AuthorProvisioningInternalController(AuthorProvisioningService authorProvisioningService) {
        this.authorProvisioningService = authorProvisioningService;
    }

    @PostMapping("/api/internal/publishing/sites/{siteKey}/authors")
    public AuthorProvisioningBridgeResponse provisionAuthor(
            @PathVariable String siteKey, @RequestBody AuthorProvisioningBridgeRequest request) {
        AuthorProvisioningRequest cmsRequest = new AuthorProvisioningRequest(
                request.email(), request.wpRole(), request.firstName(), request.lastName(), request.displayName(),
                request.websiteUrl(), request.bio(), request.locale());
        String cmsAuthorId = authorProvisioningService.provisionAuthor(siteKey, cmsRequest);
        return new AuthorProvisioningBridgeResponse(cmsAuthorId);
    }
}
