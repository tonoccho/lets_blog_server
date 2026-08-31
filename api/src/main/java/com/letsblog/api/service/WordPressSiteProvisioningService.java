package com.letsblog.api.service;

import com.letsblog.api.aop.AuditLog;
import com.letsblog.api.cms.CmsType;
import com.letsblog.api.domain.AuditLogAction;
import com.letsblog.api.domain.Site;
import com.letsblog.api.dto.AdoptWordPressSiteRequest;
import com.letsblog.api.dto.CreateManagedWordPressSiteRequest;
import com.letsblog.api.dto.SiteRegisterRequest;
import com.letsblog.api.dto.SiteResponse;
import com.letsblog.api.provisioning.WordPressProvisioningClient;
import com.letsblog.api.provisioning.WordPressSyncClient;
import com.letsblog.api.repository.PostRepository;
import com.letsblog.api.repository.SiteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 常駐wordpressコンテナ上へのサブディレクトリ設置型WordPress自動構築を統括する。
 * 構築完了後は既存のSiteService.register()(カテゴリ/タグ/著者プロビジョニングを含む)へ接続し、
 * インフラ構築とリソース初期化を1回のサイト作成操作で完結させる。
 */
@Service
public class WordPressSiteProvisioningService {

    private final WordPressProvisioningClient provisioningClient;
    private final WordPressSyncClient syncClient;
    private final SiteService siteService;
    private final SiteRepository siteRepository;
    private final PostRepository postRepository;

    public WordPressSiteProvisioningService(WordPressProvisioningClient provisioningClient,
                                             WordPressSyncClient syncClient,
                                             SiteService siteService,
                                             SiteRepository siteRepository,
                                             PostRepository postRepository) {
        this.provisioningClient = provisioningClient;
        this.syncClient = syncClient;
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
        String locale = (request.locale() != null && !request.locale().isBlank()) ? request.locale() : "ja";

        WordPressProvisioningClient.ProvisionResult result;
        try {
            result = provisioningClient.provision(
                    new WordPressProvisioningClient.ProvisionCommand(
                            slug, dbName, request.title(), request.adminUser(), request.adminEmail(),
                            request.adminPassword(), locale));
        } catch (ProvisioningException e) {
            // エージェント側の自己クリーンアップが働かなかった場合(接続断など)の保険的な後始末。
            // 「既に存在する」場合(SiteAlreadyProvisionedException)は今回のリクエストで
            // 何も作成していないため、ここでは捕捉せずそのまま呼び出し元へ伝播する(issue #315)。
            provisioningClient.deprovision(slug, dbName);
            throw e;
        }

        // credentials.baseUrlはSpring Boot API自身がREST呼び出しに使う値のため、
        // ブラウザ向けの公開URL(https://localhost/sites/{slug}、reverse-proxy経由)ではなく、
        // lbs-net内部で直接到達できるwordpressコンテナのURLを使う(LLM/ComfyUI等と同じ内部直結方式)。
        // transport=AGENTにより、通常のブログ運用操作(投稿・カテゴリ/タグ解決・著者・メディア・疎通確認)は
        // このURLへのREST呼び出しではなく、常駐wordpressコンテナ内のプロビジョニングエージェント経由の
        // wp-cli実行(WordPressAgentOperations)で行われる。username/appPasswordはエージェント経路では
        // 使われないが、フォールバック・将来的な用途のために保持しておく。
        String internalUrl = "http://wordpress/sites/" + slug;
        Map<String, String> credentials = Map.of(
                "baseUrl", internalUrl,
                "username", result.adminUser(),
                "appPassword", result.applicationPassword(),
                "transport", "AGENT",
                "wpSlug", slug);

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

        if (request.templateSiteId() != null) {
            cloneFromTemplate(request.templateSiteId(), site, slug, dbName);
        }

        return new SiteResponse(
                site.getId(), site.getName(), site.getSiteKey(), site.getCmsType(), site.getBaseUrl(),
                site.getCreatedAt(), site.getUpdatedAt(), response.connectionCheckStatus(), true, false);
    }

    /**
     * DBには未登録だが、provisioning agent側には実体(ディレクトリ・DB)が既に存在する
     * WordPressサイトを取り込んで登録する(issue #317)。既存の管理ユーザーに対して新しい
     * Application Passwordを発行するのみで、新規構築は行わない。
     * このリクエストはサイト実体を作成していないため、途中で失敗してもdeprovisionは行わない
     * (#315と同じ理由: このリクエストが作っていないものを消してはならない)。
     */
    @AuditLog(action = AuditLogAction.WORDPRESS_ADOPTED, resourceType = "SITE")
    @Transactional
    public SiteResponse adoptManagedSite(AdoptWordPressSiteRequest request, Long actorId) {
        if (siteRepository.existsBySiteKey(request.siteKey())) {
            throw new IllegalArgumentException("siteKey '" + request.siteKey() + "' は既に登録されています");
        }

        String slug = normalizeSlug(request.siteKey());
        String dbName = "wp_" + slug;

        WordPressProvisioningClient.ProvisionResult result = provisioningClient.adopt(
                new WordPressProvisioningClient.AdoptCommand(slug, request.adminUser()));

        String internalUrl = "http://wordpress/sites/" + slug;
        Map<String, String> credentials = Map.of(
                "baseUrl", internalUrl,
                "username", result.adminUser(),
                "appPassword", result.applicationPassword(),
                "transport", "AGENT",
                "wpSlug", slug);

        SiteRegisterRequest registerRequest = new SiteRegisterRequest(
                request.name(), request.siteKey(), CmsType.WORDPRESS, credentials);

        SiteResponse response = siteService.register(registerRequest, actorId);

        Site site = siteRepository.findBySiteKey(request.siteKey())
                .orElseThrow(() -> new SiteNotFoundException("siteKey '" + request.siteKey() + "' は登録されていません"));
        site.setManagedWordpress(true);
        site.setWpSlug(slug);
        site.setWpDbName(dbName);
        site.setBaseUrl(result.url());
        siteRepository.save(site);

        return new SiteResponse(
                site.getId(), site.getName(), site.getSiteKey(), site.getCmsType(), site.getBaseUrl(),
                site.getCreatedAt(), site.getUpdatedAt(), response.connectionCheckStatus(), true, false);
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

    /**
     * テンプレートサイトのテーマ・プラグイン・メディア・DB(コンテンツ)を新規サイトへ複製する。
     * DB同期はwp_users/wp_usermetaを対象外にするため、直前に発行した新規サイトの管理者アカウントは
     * 上書きされずそのまま有効(WordPressSyncClient/provision-agentの/syncハンドラの既存仕様)。
     */
    private void cloneFromTemplate(Long templateSiteId, Site newSite, String slug, String dbName) {
        Site templateSite = siteRepository.findById(templateSiteId)
                .orElseThrow(() -> new SiteNotFoundException(
                        "id " + templateSiteId + " のテンプレートサイトは登録されていません"));
        if (!templateSite.isManagedWordpress()) {
            provisioningClient.deprovision(slug, dbName);
            siteRepository.delete(newSite);
            throw new IllegalArgumentException("テンプレートには自動構築サイトのみ指定できます");
        }
        try {
            syncClient.sync(new WordPressSyncClient.SyncCommand(
                    templateSite.getWpSlug(), templateSite.getWpDbName(),
                    slug, dbName, List.of("themes", "plugins", "media", "db")));
        } catch (RuntimeException e) {
            provisioningClient.deprovision(slug, dbName);
            siteRepository.delete(newSite);
            throw e;
        }
    }

    private String normalizeSlug(String siteKey) {
        return siteKey.toLowerCase().replaceAll("[^a-z0-9-]", "-");
    }
}
