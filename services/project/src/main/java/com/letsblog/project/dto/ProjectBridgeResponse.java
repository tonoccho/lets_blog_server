package com.letsblog.project.dto;

import com.letsblog.project.domain.Project;

/**
 * legacy-apiに残るドメイン(一括管理/環境間比較・投稿publish・記事プレビュー等、CMSアダプタ/SSH実行への
 * 深い依存のためlegacy-apiに残る。ProjectApiKeyService等の分析/AI資格情報ドメインは#577の対象外)が、
 * プロジェクトの基本情報を参照するための内部API応答(issue #577 stage3)。
 */
public record ProjectBridgeResponse(
        Long id, String name, String slug, String masterEnvironment,
        Long localSiteId, Long testSiteId, Long productionSiteId, String githubRepository) {

    public static ProjectBridgeResponse from(Project project) {
        return new ProjectBridgeResponse(
                project.getId(), project.getName(), project.getSlug(), project.getMasterEnvironment(),
                project.getLocalSiteId(), project.getTestSiteId(), project.getProductionSiteId(),
                project.getGithubRepository());
    }
}
