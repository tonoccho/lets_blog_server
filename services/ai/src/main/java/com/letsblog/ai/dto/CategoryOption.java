package com.letsblog.ai.dto;

/**
 * プロジェクトのマスター環境サイトに既に存在するカテゴリ(親カテゴリ名付き)。
 * legacy-apiのCmsAdapter.CategoryOptionと同じ形。CmsAdapter自体はSite/CMS領域(legacy-api)に
 * 残るため、ブリッジ経由(/api/internal/ai/projects/{projectId}/existing-categories-with-parents)の
 * レスポンス形として、ai-service側にも同じ形のDTOを持つ(issue #574)。
 */
public record CategoryOption(String name, String parentName) {
}
