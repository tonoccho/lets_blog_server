package com.letsblog.project.service;

import com.letsblog.project.client.IdentityBridgeClient;
import java.util.Set;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * legacy-apiのAdminAuthorizationServiceのうち、project-serviceの移設対象(SshKeyPairController/
 * TagDesignSettingController)が必要とするrequireAdmin/requireProjectMemberOrAdminを移設する
 * (issue #577)。
 *
 * <p>admin判定はCurrentActorService経由でidentity-serviceへ委ねる(log-writer/media-service/
 * ai-service/content-serviceと同じ暫定策)。プロジェクトメンバー判定は、project_userテーブルが
 * issue #583でidentity-serviceが所有する(ADR-0004によりクロス
 * スキーマアクセス不可)ため、identity-serviceの内部ブリッジ({@link IdentityBridgeClient#isProjectMember})
 * 経由で呼び出し元のBearerトークンを転送して問い合わせる。
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
     * プロジェクトメンバーまたはadmin権限を持つユーザーのみを許可する(legacy-api時代の
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

    /**
     * 一覧系エンドポイントが「操作者が見てよい範囲」を絞るための判定材料(issue #830)。
     *
     * <p>admin は全件を見られるので {@link Optional#empty()} を返す。それ以外は
     * <b>所属プロジェクトのID集合</b>を返す(空集合なら、どのプロジェクトにも属していない=
     * 一覧は空になる)。呼び出し側は {@code isEmpty()} を「制限なし」と読む。
     *
     * <p>行ごとに {@link #requireProjectMemberOrAdmin} を呼ぶと N+1 になるため、
     * まとめて引いてメモリ上で絞る。
     */
    public Optional<Set<Long>> accessibleProjectIds() {
        if (currentActorService.isAdmin()) {
            return Optional.empty();
        }
        Long actorId = currentActorService.getCurrentActorId();
        if (actorId == null) {
            throw new ForbiddenException("この操作にはログインが必要です");
        }
        return Optional.of(Set.copyOf(
                identityBridgeClient.projectIdsForUser(actorId, currentActorService.getAuthorizationHeader())));
    }
}
