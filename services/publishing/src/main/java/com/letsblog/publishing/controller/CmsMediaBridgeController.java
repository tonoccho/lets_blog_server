package com.letsblog.publishing.controller;

import com.letsblog.publishing.cms.CmsAdapter;
import com.letsblog.publishing.cms.CmsAdapterFactory;
import com.letsblog.publishing.cms.CmsApiException;
import com.letsblog.publishing.cms.CmsCredentials;
import com.letsblog.publishing.cms.MediaUploadResult;
import com.letsblog.publishing.domain.Project;
import com.letsblog.publishing.domain.Site;
import com.letsblog.publishing.dto.MediaGcScanBridgeResponse;
import com.letsblog.publishing.service.ProjectService;
import com.letsblog.publishing.service.SiteService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * media-service向けの内部CMSブリッジ(issue #573 stage3で新設、issue #709でlegacy-apiから
 * publishing-serviceへ移管、Epic #551 C6-3)。CMS(現状WordPressのみ)への実際の接続情報
 * ({@link CmsCredentials}、SSH鍵等の秘匿情報を含む)は{@link SiteService}が復号して保持したまま
 * publishing-serviceの外へは一切出さず、media-serviceからは「このsite/projectに対して
 * アップロード/一覧取得/削除を実行してほしい」という操作の依頼のみを受け取り、ここで
 * {@link CmsAdapter}を解決して実行する。
 *
 * <p>認可は、media-service側で既にrequireAdmin等のチェックを済ませたリクエストのBearerトークンを
 * そのまま転送してもらう想定で、ここでは追加の認可チェックは行わない(移管元のlegacy-api版と同じ、
 * AUTHORIZATION_MATRIX.md参照)。パスは移管元の{@code /api/internal/cms/**}から、publishing-service内の
 * 他の内部ブリッジ({@link AuthorProvisioningInternalController}等)と同じ
 * {@code /api/internal/{owning-service}/**}命名規則に合わせて{@code /api/internal/publishing/**}へ
 * 変更した(issue #709のレビュー指摘対応)。media-serviceの{@code CmsBridgeClient}側の呼び出しパスも
 * 追従済み。
 *
 * <p>{@code Project}/{@code Site}本体の所有権はproject-serviceにある(issue #577 stage2)。
 * {@link ProjectService}/{@link SiteService}がproject-serviceへの内部ブリッジ経由でプロジェクト/
 * サイトの基本情報を取得する(issue #708でpublishing-serviceへ移設済み)。
 */
@RestController
public class CmsMediaBridgeController {

    private final ProjectService projectService;
    private final SiteService siteService;
    private final CmsAdapterFactory cmsAdapterFactory;

    public CmsMediaBridgeController(
            ProjectService projectService,
            SiteService siteService,
            CmsAdapterFactory cmsAdapterFactory) {
        this.projectService = projectService;
        this.siteService = siteService;
        this.cmsAdapterFactory = cmsAdapterFactory;
    }

    @PostMapping(
            value = "/api/internal/publishing/sites/{site}/media",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MediaUploadResult uploadMedia(@PathVariable String site, @RequestPart("file") MultipartFile file) {
        try {
            CmsCredentials credentials = siteService.getCredentials(site);
            CmsAdapter cmsAdapter = cmsAdapterFactory.resolve(credentials.cmsType());
            return cmsAdapter.uploadMedia(credentials, file.getOriginalFilename(), file.getContentType(), file.getBytes());
        } catch (IOException e) {
            throw new CmsApiException("画像の読み込みに失敗しました", e);
        }
    }

    @GetMapping("/api/internal/publishing/projects/{projectId}/media-scan")
    public MediaGcScanBridgeResponse scanMedia(@PathVariable Long projectId, @RequestParam String environment) {
        Project project = getProject(projectId);
        Site site = resolveSite(project, environment);
        CmsCredentials credentials = siteService.getCredentials(site.getSiteKey());
        CmsAdapter adapter = cmsAdapterFactory.resolve(credentials.cmsType());
        return new MediaGcScanBridgeResponse(adapter.listMedia(credentials), adapter.scanMediaReferences(credentials));
    }

    @DeleteMapping("/api/internal/publishing/projects/{projectId}/media/{mediaId}")
    public ResponseEntity<Void> deleteMedia(
            @PathVariable Long projectId, @PathVariable String mediaId, @RequestParam String environment) {
        Project project = getProject(projectId);
        Site site = resolveSite(project, environment);
        CmsCredentials credentials = siteService.getCredentials(site.getSiteKey());
        CmsAdapter adapter = cmsAdapterFactory.resolve(credentials.cmsType());
        adapter.deleteMedia(credentials, mediaId);
        return ResponseEntity.noContent().build();
    }

    private Site resolveSite(Project project, String environment) {
        Long siteId = switch (environment) {
            case "local" -> project.getLocalSiteId();
            case "test" -> project.getTestSiteId();
            case "production" -> project.getProductionSiteId();
            default -> throw new IllegalArgumentException("不正な環境です: " + environment);
        };
        if (siteId == null) {
            throw new IllegalArgumentException("環境 '" + environment + "' にはサイトが設定されていません");
        }
        return siteService.getById(siteId)
                .orElseThrow(() -> new IllegalArgumentException("環境 '" + environment + "' にはサイトが設定されていません"));
    }

    private Project getProject(Long projectId) {
        return projectService.getProjectEntity(projectId);
    }
}
