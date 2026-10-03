package com.letsblog.project.dto;

import com.letsblog.project.domain.Project;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * legacy-apiに残るドメイン(一括管理/環境間比較・投稿publish・記事プレビュー等、CMSアダプタ/SSH実行への
 * 深い依存のためlegacy-apiに残る。ProjectApiKeyService等の分析/AI資格情報ドメインは#577の対象外)が、
 * プロジェクトの基本情報を参照するための内部API応答(issue #577 stage3)。
 */
public record ProjectBridgeResponse(
        Long id, String name, String slug, String masterEnvironment,
        Long localSiteId, Long testSiteId, Long productionSiteId, String githubRepository,
        Instant createdAt, Instant updatedAt) {

    public static ProjectBridgeResponse from(Project project) {
        return new ProjectBridgeResponse(
                project.getId(), project.getName(), project.getSlug(), project.getMasterEnvironment(),
                project.getLocalSiteId(), project.getTestSiteId(), project.getProductionSiteId(),
                project.getGithubRepository(), toInstant(project.getCreatedAt()), toInstant(project.getUpdatedAt()));
    }

    // DB / エンティティの LocalDateTime は UTC の壁時計(#1257)。内部ブリッジも Z 終端 RFC 3339 で返す(#1541)。
    private static Instant toInstant(LocalDateTime utcWallClock) {
        return utcWallClock == null ? null : utcWallClock.toInstant(ZoneOffset.UTC);
    }
}
