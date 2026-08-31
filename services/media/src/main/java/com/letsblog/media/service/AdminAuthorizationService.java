package com.letsblog.media.service;

import com.letsblog.media.client.IdentityBridgeClient;
import org.springframework.stereotype.Service;

/**
 * legacy-apiのAdminAuthorizationServiceのうち、ログ読み取りAPIに必要なrequireAdminのみを
 * 移設(#572)。実際のadmin判定はCurrentActorService経由でidentity-serviceへ委ねる。
 *
 * <p>issue #830 で、ダイアグラム・生成画像をプロジェクト単位で絞るために
 * {@code requireProjectMemberOrAdmin}を追加した。プロジェクトメンバー判定は、
 * {@code project_users}がlegacy-apiに残っている(ADR-0004によりクロススキーマアクセス不可)ため
 * {@link IdentityBridgeClient#isProjectMember}経由で問い合わせる
 * (ai/content/analytics/publishingと同じ方針)。
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

    /** プロジェクトメンバーまたはadminのみを許可する(issue #830)。 */
    public void requireProjectMemberOrAdmin(Long projectId) {
        if (currentActorService.isAdmin()) {
            return;
        }
        Long actorId = currentActorService.getCurrentActorId();
        if (actorId == null) {
            throw new ForbiddenException("この操作にはログインが必要です");
        }
        if (!identityBridgeClient.isProjectMember(
                projectId, actorId, currentActorService.getAuthorizationHeader())) {
            throw new ForbiddenException("この操作にはプロジェクトメンバーまたはadmin権限が必要です");
        }
    }

    /**
     * プロジェクトに属するリソース(ダイアグラム・生成画像)への操作を、そのプロジェクトの
     * メンバーまたはadminに限定する(issue #830)。
     *
     * <p>どのプロジェクトにも紐付いていないリソースは{@code projectId}がnullになりうる。
     * 判定に使えるメンバーシップが存在しないため、その場合はadminのみを許可する。
     * 「認証済みなら誰でも」のまま残すと、projectIdを空で作ったリソースが
     * 他人からの読み書き・削除の抜け道になるため。
     */
    public void requireProjectMemberOrAdminForResource(Long projectId) {
        if (projectId == null) {
            requireAdmin();
            return;
        }
        requireProjectMemberOrAdmin(projectId);
    }
}
