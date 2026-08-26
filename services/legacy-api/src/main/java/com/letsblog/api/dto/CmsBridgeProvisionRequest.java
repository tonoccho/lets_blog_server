package com.letsblog.api.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/** {@code CmsProvisioningBridgeController#provision}へのリクエストボディ。actorEmailはnull可(著者プロビジョニングをスキップ)。 */
public record CmsBridgeProvisionRequest(
        @NotNull String cmsType,
        @NotEmpty Map<String, String> credentials,
        String actorEmail
) {
}
