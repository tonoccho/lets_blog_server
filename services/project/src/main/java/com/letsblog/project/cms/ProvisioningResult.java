package com.letsblog.project.cms;

/**
 * legacy-apiのCmsProvisioningBridgeController#provisionの応答(issue #577 stage2)。
 * カテゴリ/タグ/著者は個別に失敗しうる(部分的失敗を許容する、legacy-apiのProvisioningServiceと同じ方針)。
 */
public record ProvisioningResult(
        String defaultCategoryId, String categoryError,
        String defaultTagId, String tagError,
        String authorId, String authorError) {
}
