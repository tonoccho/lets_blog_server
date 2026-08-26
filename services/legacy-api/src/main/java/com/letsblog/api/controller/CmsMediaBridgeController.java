package com.letsblog.api.controller;

import com.letsblog.api.cms.CmsAdapter;
import com.letsblog.api.cms.CmsAdapterFactory;
import com.letsblog.api.cms.CmsApiException;
import com.letsblog.api.cms.CmsCredentials;
import com.letsblog.api.cms.MediaUploadResult;
import com.letsblog.api.domain.Project;
import com.letsblog.api.domain.Site;
import com.letsblog.api.dto.MediaGcScanBridgeResponse;
import com.letsblog.api.service.ProjectService;
import com.letsblog.api.service.SiteService;
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
 * media-service向けの内部CMSブリッジ(issue #573 stage3)。CMS(現状WordPressのみ)への実際の
 * 接続情報({@link CmsCredentials}、SSH鍵等の秘匿情報を含む)は{@link SiteService}が復号して
 * 保持したままlegacy-apiの外へは一切出さず、media-serviceからは「このsite/projectに対して
 * アップロード/一覧取得/削除を実行してほしい」という操作の依頼のみを受け取り、legacy-api側で
 * {@link CmsAdapter}を解決して実行する。
 *
 * <p>元々{@link com.letsblog.api.controller.MediaController}/
 * {@link com.letsblog.api.service.MediaGarbageCollectionService}が持っていたのと同じロジックを
 * ここへ引き継いだ。認可は、media-service側で既にrequireAdmin等のチェックを済ませたリクエストの
 * Bearerトークンをそのまま転送してもらう想定で、ここでは追加の認可チェックは行わない
 * (元のMediaController.uploadも認可チェックなしだった。AUTHORIZATION_MATRIX.md参照)。
 *
 * <p>{@code Project}/{@code Site}本体の所有権はproject-serviceへ移った(issue #577 stage2)。
 * {@link ProjectService}/{@link SiteService}がproject-serviceへの内部ブリッジ経由でプロジェクト/
 * サイトの基本情報を取得する(issue #577 stage3。ローカルJPAエンティティへの直接アクセスは廃止した)。
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

    @PostMapping(value = "/api/internal/cms/sites/{site}/media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MediaUploadResult uploadMedia(@PathVariable String site, @RequestPart("file") MultipartFile file) {
        try {
            CmsCredentials credentials = siteService.getCredentials(site);
            CmsAdapter cmsAdapter = cmsAdapterFactory.resolve(credentials.cmsType());
            return cmsAdapter.uploadMedia(credentials, file.getOriginalFilename(), file.getContentType(), file.getBytes());
        } catch (IOException e) {
            throw new CmsApiException("画像の読み込みに失敗しました", e);
        }
    }

    @GetMapping("/api/internal/cms/projects/{projectId}/media-scan")
    public MediaGcScanBridgeResponse scanMedia(@PathVariable Long projectId, @RequestParam String environment) {
        Project project = getProject(projectId);
        Site site = resolveSite(project, environment);
        CmsCredentials credentials = siteService.getCredentials(site.getSiteKey());
        CmsAdapter adapter = cmsAdapterFactory.resolve(credentials.cmsType());
        return new MediaGcScanBridgeResponse(adapter.listMedia(credentials), adapter.scanMediaReferences(credentials));
    }

    @DeleteMapping("/api/internal/cms/projects/{projectId}/media/{mediaId}")
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
