package com.letsblog.api.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * project-service向けの内部CMSブリッジ(issue #577 stage2、{@code CmsProvisioningBridgeController})への
 * リクエストボディ。credentialsはSiteService#buildCredentialsFromMapと同じ生のMap表現
 * (baseUrl/username/transport/sshHost等)。project-serviceが復号したその場限りの認証情報を
 * このリクエストの間だけ受け取り、legacy-api側では永続化しない。
 */
public record CmsBridgeCredentialsRequest(
        @NotNull String cmsType,
        @NotEmpty Map<String, String> credentials
) {
}
