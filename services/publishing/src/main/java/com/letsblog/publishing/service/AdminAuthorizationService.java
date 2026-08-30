package com.letsblog.publishing.service;

import com.letsblog.publishing.client.LegacyApiBridgeClient;
import org.springframework.stereotype.Service;

/**
 * legacy-apiの{@code com.letsblog.api.service.AdminAuthorizationService}のうち、
 * {@code com.letsblog.publishing.controller.BulkManagementController}が使う{@code requireAdmin}を
 * publishing-serviceへ移設したもの(issue #708)。issue #712(Epic #551 C6-6)で
 * {@code ArticlePreviewController}が移設されたのに伴い、{@code requireProjectMemberOrAdmin}も
 * 追加した。{@code requireSelfOrAdmin}は本サービスのどの機能でも使わないため移設しない。
 *
 * <p>admin判定はCurrentActorService経由でidentity-serviceへ委ねる。プロジェクトメンバー判定は、
 * {@code project_user}テーブルがlegacy-apiに残っている(ADR-0004によりクロススキーマアクセス不可)ため、
 * legacy-apiの内部ブリッジ({@link LegacyApiBridgeClient#isProjectMember})経由で問い合わせる
 * (content-service/ai-service/analytics-serviceと同じ方針)。
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
        if (!legacyApiBridgeClient.isProjectMember(projectId, actorId)) {
            throw new ForbiddenException("この操作にはプロジェクトメンバーまたはadmin権限が必要です");
        }
    }
}
