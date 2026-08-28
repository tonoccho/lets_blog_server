package com.letsblog.publishing.dto;

import com.letsblog.publishing.service.ProvisioningService;

/** {@code CmsProvisioningBridgeController#provision}のレスポンス。{@link ProvisioningService.ProvisioningResult}をそのまま転写する。 */
public record CmsBridgeProvisionResponse(
        String defaultCategoryId, String categoryError,
        String defaultTagId, String tagError,
        String authorId, String authorError) {

    public static CmsBridgeProvisionResponse from(ProvisioningService.ProvisioningResult result) {
        return new CmsBridgeProvisionResponse(
                result.defaultCategoryId, result.categoryError,
                result.defaultTagId, result.tagError,
                result.authorId, result.authorError);
    }
}
