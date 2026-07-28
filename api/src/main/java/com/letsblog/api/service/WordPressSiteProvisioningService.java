package com.letsblog.api.service;

import com.letsblog.api.aop.AuditLog;
import com.letsblog.api.cms.CmsType;
import com.letsblog.api.domain.AuditLogAction;
import com.letsblog.api.domain.Site;
import com.letsblog.api.dto.CreateManagedWordPressSiteRequest;
import com.letsblog.api.dto.SiteRegisterRequest;
import com.letsblog.api.dto.SiteResponse;
import com.letsblog.api.provisioning.WordPressProvisioningClient;
import com.letsblog.api.repository.PostRepository;
import com.letsblog.api.repository.SiteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * 常駐wordpressコンテナ上へのサブディレクトリ設置型WordPress自動構築を統括する。
 * 構築完了後は既存のSiteService.register()(カテゴリ/タグ/著者プロビジョニングを含む)へ接続し、
 * インフラ構築とリソース初期化を1回のサイト作成操作で完結させる。
 */
@Service
public class WordPressSiteProvisioningService {

    private final WordPressProvisioningClient provisioningClient;
    private final SiteService siteService;
    private final SiteRepository siteRepository;
    private final PostRepository postRepository;

    public WordPressSiteProvisioningService(WordPressProvisioningClient provisioningClient,
                                             SiteService siteService,
                                             SiteRepository siteRepository,
                                             PostRepository postRepository) {
        this.provisioningClient = provisioningClient;
        this.siteService = siteService;
        this.siteRepository = siteRepository;
        this.postRepository = postRepository;
    }

    @AuditLog(action = AuditLogAction.WORDPRESS_PROVISIONED, resourceType = "SITE")
    @Transactional
    public SiteResponse createManagedSite(CreateManagedWordPressSiteRequest request, Long actorId) {
        if (siteRepository.existsBySiteKey(request.siteKey())) {
            throw new IllegalArgumentException("siteKey '" + request.siteKey() + "' は既に登録されています");
        }

        String slug = normalizeSlug(request.siteKey());
        String dbName = "wp_" + slug;

        WordPressProvisioningClient.ProvisionResult result = provisioningClient.provision(
                new WordPressProvisioningClient.ProvisionCommand(
                        slug, dbName, request.title(), request.adminUser(), request.adminEmail(),
                        request.adminPassword()));

        // credentials.baseUrlはSpring Boot API自身がREST呼び出しに使う値のため、
        // ブラウザ向けの公開URL(https://localhost/sites/{slug}、reverse-proxy経由)ではなく、
        // lbs-net内部で直接到達できるwordpressコンテナのURLを使う(Ollama/ComfyUI等と同じ内部直結方式)。
        String internalUrl = "http://wordpress/sites/" + slug;
        Map<String, String> credentials = Map.of(
                "baseUrl", internalUrl,
                "username", result.adminUser(),
                "appPassword", result.applicationPassword());

        SiteRegisterRequest registerRequest = new SiteRegisterRequest(
                request.name(), request.siteKey(), CmsType.WORDPRESS, credentials);

        SiteResponse response;
        try {
            response = siteService.register(registerRequest, actorId);
        } catch (RuntimeException e) {
            // サイト登録(DB保存)に失敗した場合、既に構築済みのWPインスタンス・DBを削除して整合性を保つ
            provisioningClient.deprovision(slug, dbName);
            throw e;
        }

        Site site = siteRepository.findBySiteKey(request.siteKey())
                .orElseThrow(() -> new SiteNotFoundException("siteKey '" + request.siteKey() + "' は登録されていません"));
        site.setManagedWordpress(true);
        site.setWpSlug(slug);
        site.setWpDbName(dbName);
        // 表示・ブラウザアクセス用には公開URL(https://localhost/sites/{slug})を使う
        site.setBaseUrl(result.url());
        siteRepository.save(site);

        return new SiteResponse(
                site.getId(), site.getName(), site.getSiteKey(), site.getCmsType(), site.getBaseUrl(),
                site.getCreatedAt(), site.getUpdatedAt(), response.connectionCheckStatus(), true);
    }

    @AuditLog(action = AuditLogAction.SITE_DELETED, resourceType = "SITE")
    @Transactional
    public void deleteSite(Long siteId) {
        Site site = siteRepository.findById(siteId)
                .orElseThrow(() -> new SiteNotFoundException("id " + siteId + " のサイトは登録されていません"));

        if (site.isManagedWordpress()) {
            provisioningClient.deprovision(site.getWpSlug(), site.getWpDbName());
        }
        postRepository.deleteBySiteId(siteId);
        siteRepository.delete(site);
    }

    private String normalizeSlug(String siteKey) {
        return siteKey.toLowerCase().replaceAll("[^a-z0-9-]", "-");
    }
}
