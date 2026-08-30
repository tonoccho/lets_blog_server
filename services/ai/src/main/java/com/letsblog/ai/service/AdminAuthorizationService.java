package com.letsblog.ai.service;

import com.letsblog.ai.client.LegacyApiBridgeClient;
import org.springframework.stereotype.Service;

/**
 * legacy-apiのAdminAuthorizationServiceのうち、ai-serviceの移設対象(ArticlePlanController等)が
 * 必要とするrequireAdmin/requireProjectMemberOrAdminを移設する(issue #574)。
 *
 * <p>admin判定はCurrentActorService経由でidentity-serviceへ委ねる(log-writer(#572)/
 * media-service(#573)と同じ暫定策)。プロジェクトメンバー判定は、project_userテーブルが
 * project-serviceが未抽出のままlegacy-apiに残っている(ADR-0004によりクロススキーマアクセス
 * 不可)ため、legacy-apiの内部ブリッジ({@link LegacyApiBridgeClient#isProjectMember})経由で
 * 呼び出し元のBearerトークンを転送して問い合わせる。
 */
@Service
public class AdminAuthorizationService {

    private final CurrentActorService currentActorService;
    private final LegacyApiBridgeClient legacyApiBridgeClient;

    public AdminAuthorizationService(
            CurrentActorService currentActorService, LegacyApiBridgeClient legacyApiBridgeClient) {
        this.currentActorService = currentActorService;
        this.legacyApiBridgeClient = legacyApiBridgeClient;
    }

    public void requireAdmin() {
        if (!currentActorService.isAdmin()) {
            throw new ForbiddenException("この操作にはadmin権限が必要です");
        }
    }

    /**
     * プロジェクトメンバーまたはadmin権限を持つユーザーのみを許可する(legacy-apiの
     * AdminAuthorizationService#requireProjectMemberOrAdminと同じ方針)。
     */
    public void requireProjectMemberOrAdmin(Long projectId) {
        if (currentActorService.isAdmin()) {
            return;
        }
        Long actorId = currentActorService.getCurrentActorId();
        if (actorId == null) {
            throw new ForbiddenException("この操作にはログインが必要です");
        }
        if (!legacyApiBridgeClient.isProjectMember(projectId, actorId, currentActorService.getAuthorizationHeader())) {
            throw new ForbiddenException("この操作にはプロジェクトメンバーまたはadmin権限が必要です");
        }
    }
}
