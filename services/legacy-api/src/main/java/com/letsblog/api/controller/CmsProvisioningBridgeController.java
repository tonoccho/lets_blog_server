package com.letsblog.api.controller;

import com.letsblog.api.cms.CmsAdapter;
import com.letsblog.api.cms.CmsAdapterFactory;
import com.letsblog.api.cms.CmsCredentials;
import com.letsblog.api.cms.CmsType;
import com.letsblog.api.cms.ConnectionCheckResult;
import com.letsblog.api.cms.WpCliInstallResult;
import com.letsblog.api.cms.ssh.WordPressSshOperations;
import com.letsblog.api.dto.CmsBridgeConnectionCheckResponse;
import com.letsblog.api.dto.CmsBridgeCredentialsRequest;
import com.letsblog.api.dto.CmsBridgeExportDatabaseResponse;
import com.letsblog.api.dto.CmsBridgeProvisionRequest;
import com.letsblog.api.dto.CmsBridgeProvisionResponse;
import com.letsblog.api.service.ProvisioningService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Base64;

/**
 * project-service向けの内部CMSブリッジ(issue #577 stage2)。project-serviceへ移設した
 * SiteService/ProvisioningService/ProjectEnvironmentSyncServiceが、サイト登録・接続確認・
 * カテゴリ/タグ/著者プロビジョニング・環境同期のために実際のWordPress操作(SSH/wp-cliエージェント経由)を
 * 必要とするが、その実装({@link CmsAdapter}/{@link WordPressSshOperations}、SSH秘密鍵の取り扱いを含む)は
 * まだ移設せずlegacy-apiに残す(media-service(#573)のCmsMediaBridgeControllerと同じ方針: 実際の
 * CMS接続処理は集約したまま、呼び出し元サービスは「この認証情報でこの操作をしてほしい」という
 * 依頼のみを送る)。
 *
 * <p>credentialsは呼び出しの間だけ受け渡すその場限りの値であり、legacy-api側では永続化しない
 * (project-serviceが暗号化して永続化する。#577の受入基準「サイトのCMS認証情報はproject-serviceが正」)。
 */
@RestController
public class CmsProvisioningBridgeController {

    private final CmsAdapterFactory cmsAdapterFactory;
    private final ProvisioningService provisioningService;
    private final WordPressSshOperations sshOperations;

    public CmsProvisioningBridgeController(
            CmsAdapterFactory cmsAdapterFactory,
            ProvisioningService provisioningService,
            WordPressSshOperations sshOperations) {
        this.cmsAdapterFactory = cmsAdapterFactory;
        this.provisioningService = provisioningService;
        this.sshOperations = sshOperations;
    }

    @PostMapping("/api/internal/project/cms/test-connection")
    public CmsBridgeConnectionCheckResponse testConnection(@Valid @RequestBody CmsBridgeCredentialsRequest request) {
        CmsCredentials credentials = buildCredentials(request.cmsType(), request.credentials());
        CmsAdapter adapter = cmsAdapterFactory.resolve(credentials.cmsType());
        ConnectionCheckResult result = adapter.testConnection(credentials);
        return CmsBridgeConnectionCheckResponse.from(result);
    }

    @PostMapping("/api/internal/project/cms/install-wp-cli")
    public WpCliInstallResult installWpCli(@Valid @RequestBody CmsBridgeCredentialsRequest request) {
        CmsCredentials credentials = buildCredentials(request.cmsType(), request.credentials());
        CmsAdapter adapter = cmsAdapterFactory.resolve(credentials.cmsType());
        return adapter.installWpCli(credentials);
    }

    @PostMapping("/api/internal/project/cms/has-author-capability")
    public boolean hasAuthorProvisioningCapability(@Valid @RequestBody CmsBridgeCredentialsRequest request) {
        CmsCredentials credentials = buildCredentials(request.cmsType(), request.credentials());
        CmsAdapter adapter = cmsAdapterFactory.resolve(credentials.cmsType());
        return adapter.hasAuthorProvisioningCapability(credentials);
    }

    /**
     * StaticContentGenerationService(project-service)向け。SSH管理サイトの有効化済みプラグイン名一覧
     * (自動構築/managedサイトはproject-service側がwp-agentへ直接問い合わせるため、ここでは扱わない)。
     */
    @PostMapping("/api/internal/project/cms/list-active-plugins")
    public java.util.List<String> listActivePlugins(@Valid @RequestBody CmsBridgeCredentialsRequest request) {
        CmsCredentials.WordPressCredentials credentials = requireWordPress(request);
        return sshOperations.listPlugins(credentials).stream()
                .filter(info -> "active".equalsIgnoreCase(info.status()))
                .map(WordPressSshOperations.PluginThemeInfo::name)
                .toList();
    }

    @PostMapping("/api/internal/project/cms/provision")
    public CmsBridgeProvisionResponse provision(@Valid @RequestBody CmsBridgeProvisionRequest request) {
        CmsCredentials credentials = buildCredentials(request.cmsType(), request.credentials());
        ProvisioningService.ProvisioningResult result =
                provisioningService.provisionSite(credentials.cmsType(), credentials, request.actorEmail());
        return CmsBridgeProvisionResponse.from(result);
    }

    @PostMapping("/api/internal/project/cms/export-database")
    public CmsBridgeExportDatabaseResponse exportDatabase(@Valid @RequestBody CmsBridgeCredentialsRequest request) {
        CmsCredentials.WordPressCredentials credentials = requireWordPress(request);
        WordPressSshOperations.DatabaseExport export = sshOperations.exportDatabase(credentials);
        return new CmsBridgeExportDatabaseResponse(export.tablePrefix(), Base64.getEncoder().encodeToString(export.dump()));
    }

    @PostMapping(value = "/api/internal/project/cms/export-media", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> exportMedia(@Valid @RequestBody CmsBridgeCredentialsRequest request) {
        CmsCredentials.WordPressCredentials credentials = requireWordPress(request);
        return ResponseEntity.ok(sshOperations.exportMedia(credentials));
    }

    @PostMapping(value = "/api/internal/project/cms/export-themes", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> exportThemes(@Valid @RequestBody CmsBridgeCredentialsRequest request) {
        CmsCredentials.WordPressCredentials credentials = requireWordPress(request);
        return ResponseEntity.ok(sshOperations.exportThemes(credentials));
    }

    private CmsCredentials.WordPressCredentials requireWordPress(CmsBridgeCredentialsRequest request) {
        CmsCredentials credentials = buildCredentials(request.cmsType(), request.credentials());
        if (!(credentials instanceof CmsCredentials.WordPressCredentials wp)) {
            throw new IllegalArgumentException("環境同期はWordPress(SSH)サイトのみ対応しています");
        }
        return wp;
    }

    /** SiteService#buildCredentialsFromMapと同じマッピング(project-service側から見た生のcredentials表現)。 */
    private CmsCredentials buildCredentials(String cmsTypeValue, java.util.Map<String, String> credentials) {
        CmsType cmsType = CmsType.valueOf(cmsTypeValue);
        return switch (cmsType) {
            case WORDPRESS -> new CmsCredentials.WordPressCredentials(
                    credentials.get("baseUrl"),
                    credentials.get("username"),
                    credentials.get("transport"),
                    credentials.get("sshHost"),
                    parseSshPort(credentials.get("sshPort")),
                    credentials.get("sshUser"),
                    credentials.get("wpPath"),
                    credentials.get("sshPrivateKeyPem"),
                    credentials.get("sshHostKeyFingerprint"),
                    credentials.get("wpSlug"));
        };
    }

    private Integer parseSshPort(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("sshPortは数値で指定してください: " + value);
        }
    }
}
