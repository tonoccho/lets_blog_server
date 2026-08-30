package com.letsblog.api.controller;

import com.letsblog.api.domain.UserSiteAuthor;
import com.letsblog.api.dto.ArticleImageLongEdgePxBridgeResponse;
import com.letsblog.api.dto.CacheUserSiteAuthorBridgeRequest;
import com.letsblog.api.dto.UserSiteAuthorBridgeResponse;
import com.letsblog.api.repository.ProjectUserRepository;
import com.letsblog.api.repository.UserSiteAuthorRepository;
import com.letsblog.api.service.ProjectService;
import com.letsblog.api.service.ProjectUserSyncService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * project-service/publishing-service向けの内部ブリッジ(issue #577 stage2、issue #707)。
 * project_userテーブルの所有権はまだlegacy-apiに残る({@link ProjectUserSyncService}参照)ため、
 * project-serviceへ移設したProjectEnvironmentSyncServiceが環境同期(DB同期)後にWordPressユーザー
 * ロールを再整合させる際は、このブリッジ経由でlegacy-apiへ依頼する。
 *
 * <p>{@code user_site_authors}(著者マッピング)の所有権も同じ理由でlegacy-apiに残る(issue #707、
 * #575設計判断4)。publishing-serviceへ移設した{@code PostPublishService#resolveAuthorId}は、
 * このブリッジ経由で対応表を参照・キャッシュ書き込みする。あわせて、投稿画像の長編リサイズ目標px
 * (project_image_settings、AI画像生成ドメインのためlegacy-apiに残る)の解決もこのブリッジ経由で行う。
 *
 * <p>認可は、呼び出し元(project-service/publishing-service)が既にrequireAdmin/
 * requireProjectMemberOrAdmin等を済ませたリクエストのトークンをそのまま転送してもらう想定で、
 * ここでは追加の認可チェックは行わない(ContentBridgeController/AiBridgeControllerと同じ方針)。
 */
@RestController
public class ProjectUserBridgeController {

    private final ProjectUserSyncService projectUserSyncService;
    private final UserSiteAuthorRepository userSiteAuthorRepository;
    private final ProjectService projectService;
    private final ProjectUserRepository projectUserRepository;

    public ProjectUserBridgeController(
            ProjectUserSyncService projectUserSyncService, UserSiteAuthorRepository userSiteAuthorRepository,
            ProjectService projectService, ProjectUserRepository projectUserRepository) {
        this.projectUserSyncService = projectUserSyncService;
        this.userSiteAuthorRepository = userSiteAuthorRepository;
        this.projectService = projectService;
        this.projectUserRepository = projectUserRepository;
    }

    /**
     * AdminAuthorizationService(publishing-service)#requireProjectMemberOrAdminが使う、
     * プロジェクトメンバー判定(issue #712。ContentBridgeController#isProjectMemberと同じ内容を、
     * publishing-service向けの{@code /api/internal/project/**}名前空間で提供する)。
     */
    @GetMapping("/api/internal/project/projects/{projectId}/members/{userId}")
    public boolean isProjectMember(@PathVariable Long projectId, @PathVariable Long userId) {
        return projectUserRepository.findByProjectIdAndUserId(projectId, userId).isPresent();
    }

    /** ProjectEnvironmentSyncService(project-service)#sync がDB同期後に呼ぶ、サイト向けロール再整合。 */
    @PostMapping("/api/internal/project/project-users/{projectId}/sites/{siteId}/reconcile-roles")
    public ResponseEntity<Void> reconcileRolesForSite(@PathVariable Long projectId, @PathVariable Long siteId) {
        projectUserSyncService.reconcileRolesForSite(projectId, siteId);
        return ResponseEntity.noContent().build();
    }

    /**
     * publishing-serviceのPostPublishService#resolveAuthorIdが使う、著者マッピングの照会。
     * 該当が無ければ404。
     */
    @GetMapping("/api/internal/project/user-site-authors/{userId}/{siteId}")
    public ResponseEntity<UserSiteAuthorBridgeResponse> findUserSiteAuthor(
            @PathVariable Long userId, @PathVariable Long siteId) {
        return userSiteAuthorRepository.findByUserIdAndSiteId(userId, siteId)
                .map(mapping -> ResponseEntity.ok(new UserSiteAuthorBridgeResponse(mapping.getCmsAuthorId())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * publishing-serviceのPostPublishService#resolveAuthorIdのメール検索フォールバックが使う、
     * 著者マッピングのキャッシュ書き込み。
     */
    @PostMapping("/api/internal/project/user-site-authors")
    public ResponseEntity<Void> cacheUserSiteAuthor(@RequestBody CacheUserSiteAuthorBridgeRequest request) {
        UserSiteAuthor mapping = userSiteAuthorRepository
                .findByUserIdAndSiteId(request.userId(), request.siteId())
                .orElseGet(() -> new UserSiteAuthor(request.userId(), request.siteId(), request.cmsAuthorId()));
        mapping.setCmsAuthorId(request.cmsAuthorId());
        userSiteAuthorRepository.save(mapping);
        return ResponseEntity.noContent().build();
    }

    /**
     * publishing-serviceのPostPublishServiceが使う、記事投稿時に画像をリサイズする長編の目標pxの解決
     * (project_image_settings、legacy-apiに残るAI画像生成ドメイン)。
     */
    @GetMapping("/api/internal/project/projects/{projectId}/article-image-long-edge-px")
    public ArticleImageLongEdgePxBridgeResponse articleImageLongEdgePx(@PathVariable Long projectId) {
        return new ArticleImageLongEdgePxBridgeResponse(projectService.resolveArticleImageLongEdgePx(projectId));
    }
}
