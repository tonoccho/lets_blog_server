package com.letsblog.analytics.service;

import com.letsblog.analytics.client.IdentityBridgeClient;
import org.springframework.stereotype.Service;

/**
 * legacy-apiのAdminAuthorizationServiceのうち、analytics-serviceの移設対象
 * (ProjectDashboardController/InternalAnalyticsProjectSettingsController等)が必要とする
 * requireProjectMemberOrAdminを移設する(issue #578)。
 *
 * <p>admin判定はCurrentActorService経由でidentity-serviceへ委ねる(log-writer(#572)/
 * media-service(#573)/ai-service(#574)と同じ暫定策)。プロジェクトメンバー判定は、project_user
 * テーブルがproject-serviceが未抽出のままlegacy-apiに残っている(ADR-0004によりクロススキーマ
 * アクセス不可)ため、legacy-apiの内部ブリッジ({@link IdentityBridgeClient#isProjectMember})経由で
 * 呼び出し元のBearerトークンを転送して問い合わせる。
 */
@Service
public class AdminAuthorizationService {

    private final CurrentActorService currentActorService;
    private final IdentityBridgeClient identityBridgeClient;

    public AdminAuthorizationService(
            CurrentActorService currentActorService, IdentityBridgeClient identityBridgeClient) {
        this.currentActorService = currentActorService;
        this.identityBridgeClient = identityBridgeClient;
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
        if (!identityBridgeClient.isProjectMember(projectId, actorId, currentActorService.getAuthorizationHeader())) {
            throw new ForbiddenException("この操作にはプロジェクトメンバーまたはadmin権限が必要です");
        }
    }
}
