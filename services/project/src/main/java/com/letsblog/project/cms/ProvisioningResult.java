package com.letsblog.project.cms;

/**
 * publishing-serviceのCmsProvisioningBridgeController#provisionの応答(issue #577 stage2で新設、issue #710でlegacy-apiから
 * publishing-serviceへ移管)。カテゴリ/タグ/著者は個別に失敗しうる(部分的失敗を許容する、
 * publishing-service側のProvisioningServiceと同じ方針)。
 */
public record ProvisioningResult(
        String defaultCategoryId, String categoryError,
        String defaultTagId, String tagError,
        String authorId, String authorError) {
}
