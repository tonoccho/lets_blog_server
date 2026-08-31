package com.letsblog.identity.controller;

import com.letsblog.identity.dto.CacheUserSiteAuthorBridgeRequest;
import com.letsblog.identity.dto.RoleOptionResponse;
import com.letsblog.identity.dto.UserSiteAuthorBridgeResponse;
import com.letsblog.identity.service.ProjectUserSyncService;
import com.letsblog.identity.service.RoleService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 他サービス向けの内部ブリッジ(issue #583)。{@code project_users} /
 * {@code user_site_authors} / {@code roles} の所有権がidentity-serviceにあるため、
 * これらを必要とする ai / analytics / content / project / publishing の各サービスは
 * ここを経由して問い合わせる。
 *
 * <p>#583以前は同じ判定が legacy-api の {@code AiBridgeController} /
 * {@code AnalyticsBridgeController} / {@code ContentBridgeController} /
 * {@code ProjectUserBridgeController} の4箇所に同じ実装で重複していた。
 * legacy-api の解体にあわせて<b>1本へ統合</b>している。
 *
 * <p>認可は、呼び出し元が既に {@code requireAdmin} / {@code requireProjectMemberOrAdmin} 等を
 * 済ませたリクエストのBearerトークンをそのまま転送してもらう想定で、ここでは追加のチェックを
 * 行わない(他サービスの内部ブリッジと同じ方針)。gatewayのルート表には載せないため、
 * 外部から到達することはない。
 */
@RestController
@RequestMapping("/api/internal/identity")
public class InternalProjectUserController {

    private final ProjectUserSyncService projectUserSyncService;
    private final RoleService roleService;

    public InternalProjectUserController(
            ProjectUserSyncService projectUserSyncService, RoleService roleService) {
        this.projectUserSyncService = projectUserSyncService;
        this.roleService = roleService;
    }

    /**
     * 認可不要: gatewayのルート表に載っておらず外部から到達できない内部ブリッジで、呼び出し元の
     * サービスが既に認可を済ませている(issue #583)。
     *
     * <p>4サービス(ai / analytics / content / project)が使うプロジェクトメンバー判定。
     */
    @GetMapping("/projects/{projectId}/members/{userId}")
    public boolean isProjectMember(@PathVariable Long projectId, @PathVariable Long userId) {
        return projectUserSyncService.isProjectMember(projectId, userId);
    }

    /**
     * 認可不要: {@link #isProjectMember}と同じ理由(gateway非経由・呼び出し元が認可済み、issue #583)。
     *
     * <p>一覧系の絞り込み(#830)で project-service が使う、所属プロジェクトIDの一括取得。
     */
    @GetMapping("/users/{userId}/project-ids")
    public List<Long> projectIdsForUser(@PathVariable Long userId) {
        return projectUserSyncService.projectIdsForUser(userId);
    }

    /**
     * 認可不要: {@link #isProjectMember}と同じ理由(gateway非経由・呼び出し元が認可済み、issue #583)。
     *
     * <p>content-service の {@code MetadataController} が表示するロール一覧。
     */
    @GetMapping("/roles")
    public List<RoleOptionResponse> roles() {
        return roleService.getAllRoles().stream().map(RoleOptionResponse::from).toList();
    }

    /**
     * 認可不要: {@link #isProjectMember}と同じ理由(gateway非経由・呼び出し元が認可済み、issue #583)。
     *
     * <p>publishing-service の {@code PostPublishService#resolveAuthorId} が使う著者ID解決。
     * 対応が無ければ404。
     */
    @GetMapping("/user-site-authors/{userId}/{siteId}")
    public ResponseEntity<UserSiteAuthorBridgeResponse> findUserSiteAuthor(
            @PathVariable Long userId, @PathVariable Long siteId) {
        return projectUserSyncService.findCmsAuthorId(userId, siteId)
                .map(cmsAuthorId -> ResponseEntity.ok(new UserSiteAuthorBridgeResponse(cmsAuthorId)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 認可不要: {@link #isProjectMember}と同じ理由(gateway非経由・呼び出し元が認可済み、issue #583)。
     *
     * <p>publishing-service がメール検索フォールバックで見つけた著者IDのキャッシュ書き込み。
     */
    @PostMapping("/user-site-authors")
    public ResponseEntity<Void> cacheUserSiteAuthor(@RequestBody CacheUserSiteAuthorBridgeRequest request) {
        projectUserSyncService.cacheAuthorMapping(request.userId(), request.siteId(), request.cmsAuthorId());
        return ResponseEntity.noContent().build();
    }

    /**
     * 認可不要: {@link #isProjectMember}と同じ理由(gateway非経由・呼び出し元が認可済み、issue #583)。
     *
     * <p>project-service の {@code ProjectEnvironmentSyncService} が環境同期(DB同期)後に
     * WordPressユーザーロールを再整合させるために呼ぶ(#514)。
     */
    @PostMapping("/project-users/{projectId}/sites/{siteId}/reconcile-roles")
    public ResponseEntity<Void> reconcileRolesForSite(@PathVariable Long projectId, @PathVariable Long siteId) {
        projectUserSyncService.reconcileRolesForSite(projectId, siteId);
        return ResponseEntity.noContent().build();
    }
}
