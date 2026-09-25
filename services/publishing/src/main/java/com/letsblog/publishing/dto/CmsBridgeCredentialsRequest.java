package com.letsblog.publishing.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * project-service向けの内部CMSブリッジ(issue #710でlegacy-apiから移設、移設元は#577 stage2の
 * {@code CmsProvisioningBridgeController})へのリクエストボディ。credentialsはproject-serviceの
 * SiteService#buildCredentialsFromMapと同じ生のMap表現(baseUrl/username/transport/sshHost等)。
 * project-serviceが復号したその場限りの認証情報をこのリクエストの間だけ受け取り、
 * publishing-service側では永続化しない。
 */
public record CmsBridgeCredentialsRequest(
        @NotNull String cmsType,
        @NotEmpty Map<String, String> credentials
) {
}
