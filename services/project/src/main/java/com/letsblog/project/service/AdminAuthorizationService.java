package com.letsblog.project.service;

import com.letsblog.project.client.LegacyApiBridgeClient;
import org.springframework.stereotype.Service;

/**
 * legacy-apiのAdminAuthorizationServiceのうち、project-serviceの移設対象(SshKeyPairController/
 * TagDesignSettingController)が必要とするrequireAdmin/requireProjectMemberOrAdminを移設する
 * (issue #577)。
 *
 * <p>admin判定はCurrentActorService経由でidentity-serviceへ委ねる(log-writer/media-service/
 * ai-service/content-serviceと同じ暫定策)。プロジェクトメンバー判定は、project_userテーブルが
 * このstageではまだproject-serviceへ移設されずlegacy-apiに残っている(ADR-0004によりクロス
 * スキーマアクセス不可)ため、legacy-apiの内部ブリッジ({@link LegacyApiBridgeClient#isProjectMember})
 * 経由で呼び出し元のBearerトークンを転送して問い合わせる。
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
