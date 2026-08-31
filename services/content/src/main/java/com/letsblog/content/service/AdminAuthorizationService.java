package com.letsblog.content.service;

import java.util.Set;
import java.util.Optional;
import com.letsblog.content.client.LegacyApiBridgeClient;
import org.springframework.stereotype.Service;

/**
 * legacy-apiのAdminAuthorizationServiceのうち、content-serviceの移設対象(ProjectCustomTagController/
 * ArticlePreviewController等)が必要とするrequireAdmin/requireProjectMemberOrAdminを移設する(issue #576)。
 *
 * <p>admin判定はCurrentActorService経由でidentity-serviceへ委ねる(log-writer/media-service/
 * ai-serviceと同じ暫定策)。プロジェクトメンバー判定は、project_userテーブルがproject-serviceが
 * 未抽出のままlegacy-apiに残っている(ADR-0004によりクロススキーマアクセス不可)ため、legacy-apiの
 * 内部ブリッジ({@link LegacyApiBridgeClient#isProjectMember})経由で呼び出し元のBearerトークンを
 * 転送して問い合わせる。
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

    /**
     * 一覧系エンドポイントが「操作者が見てよい範囲」を絞るための判定材料(issue #830)。
     *
     * <p>admin は全件を見られるので {@link Optional#empty()} を返す。それ以外は
     * <b>アクセスできるサイトのID集合</b>を返す(空集合なら一覧は空になる)。
     */
    public Optional<Set<Long>> accessibleSiteIds() {
        if (currentActorService.isAdmin()) {
            return Optional.empty();
        }
        Long actorId = currentActorService.getCurrentActorId();
        if (actorId == null) {
            throw new ForbiddenException("この操作にはログインが必要です");
        }
        return Optional.of(Set.copyOf(legacyApiBridgeClient.accessibleSiteIds(
                actorId, currentActorService.getAuthorizationHeader())));
    }
}
