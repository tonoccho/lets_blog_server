package com.letsblog.project.controller;

import com.letsblog.project.dto.ProjectBridgeResponse;
import com.letsblog.project.dto.SiteBridgeResponse;
import com.letsblog.project.repository.SiteRepository;
import com.letsblog.project.service.ProjectService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * legacy-apiに残るドメイン(一括管理/カテゴリ・タグ・プラグイン・テーマ・投稿の環境間比較/投稿publish・
 * 記事プレビュー等、いずれもCMSアダプタ・SSH実行への深い依存のため#577では移設せずlegacy-apiに残る)が、
 * プロジェクト/サイトの基本情報(認証情報を除く)を参照するための内部API(issue #577 stage3)。
 * サイトのCMS認証情報自体は{@link SiteCredentialsInternalController}(既存、issue #577受入基準)で
 * 別途取得する。{@link SiteCredentialsInternalController}と同じ方針で、project-serviceのSecurityConfig
 * による{@code /api/internal/**}の認証必須以上の追加認可は行わない。
 */
@RestController
public class ProjectInternalController {

    private final ProjectService projectService;
    private final SiteRepository siteRepository;

    public ProjectInternalController(ProjectService projectService, SiteRepository siteRepository) {
        this.projectService = projectService;
        this.siteRepository = siteRepository;
    }

    @GetMapping("/api/internal/project/projects/{projectId}")
    public ProjectBridgeResponse project(@PathVariable Long projectId) {
        return ProjectBridgeResponse.from(projectService.getProjectEntity(projectId));
    }

    public record ProjectIdResponse(Long projectId) {
    }

    /** legacy-apiのPostPublishService#publishが使う、siteId→所属projectIdの逆引き。未紐付けならprojectId=null。 */
    @GetMapping("/api/internal/project/sites/{siteId}/project-id")
    public ProjectIdResponse projectIdForSite(@PathVariable Long siteId) {
        return new ProjectIdResponse(projectService.findProjectIdBySiteId(siteId));
    }

    @GetMapping("/api/internal/project/sites/{siteId}")
    public ResponseEntity<SiteBridgeResponse> site(@PathVariable Long siteId) {
        return siteRepository.findById(siteId)
                .map(site -> ResponseEntity.ok(SiteBridgeResponse.from(site)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/api/internal/project/sites/by-key/{siteKey}")
    public ResponseEntity<SiteBridgeResponse> siteByKey(@PathVariable String siteKey) {
        return siteRepository.findBySiteKey(siteKey)
                .map(site -> ResponseEntity.ok(SiteBridgeResponse.from(site)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** legacy-apiのContentBridgeController#sitesが使う、全サイトの基本情報一覧(サイト名表示用)。 */
    @GetMapping("/api/internal/project/sites")
    public List<SiteBridgeResponse> sites() {
        return siteRepository.findAll().stream().map(SiteBridgeResponse::from).toList();
    }
}
