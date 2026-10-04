package com.letsblog.project.service;

import com.letsblog.project.aop.AuditLog;
import com.letsblog.project.cms.CmsType;
import com.letsblog.project.domain.AuditLogAction;
import com.letsblog.project.domain.Site;
import com.letsblog.project.dto.AdoptWordPressSiteRequest;
import com.letsblog.project.dto.CreateManagedWordPressSiteRequest;
import com.letsblog.project.dto.SiteRegisterRequest;
import com.letsblog.project.dto.SiteResponse;
import com.letsblog.project.dto.UtcDateTimes;
import com.letsblog.project.messaging.DomainEventPublisher;
import com.letsblog.project.provisioning.WordPressProvisioningClient;
import com.letsblog.project.provisioning.WordPressSyncClient;
import com.letsblog.project.repository.SiteRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 常駐wordpressコンテナ上へのサブディレクトリ設置型WordPress自動構築を統括する(issue #577 stage2、
 * legacy-apiから移設)。構築完了後は既存のSiteService.register()(カテゴリ/タグ/著者プロビジョニングを含む)
 * へ接続し、インフラ構築とリソース初期化を1回のサイト作成操作で完結させる。
 *
 * <p>サイト削除時、legacy-api版が持っていたcontent-serviceへの同期の投稿削除依頼
 * (ContentServiceClient#deletePostsBySite)は、project-serviceにcontent-serviceへの内部ブリッジ
 * クライアントがまだ無いため本stageでは移設していない(#577の既知の制限。PR説明を参照)。ただし
 * {@code site.deleted}ドメインイベント(issue #580、下記{@link DomainEventPublisher})はcontent-service
 * 側が購読済みのため、非同期経路での投稿削除は行われる(即時ではなくイベント配送後になる)。
 */
@Slf4j
@Service
public class WordPressSiteProvisioningService {

    private final WordPressProvisioningClient provisioningClient;
    private final WordPressSyncClient syncClient;
    private final SiteService siteService;
    private final SiteRepository siteRepository;
    private final DomainEventPublisher domainEventPublisher;

    public WordPressSiteProvisioningService(WordPressProvisioningClient provisioningClient,
            WordPressSyncClient syncClient, SiteService siteService, SiteRepository siteRepository,
            DomainEventPublisher domainEventPublisher) {
        this.provisioningClient = provisioningClient;
        this.syncClient = syncClient;
        this.siteService = siteService;
        this.siteRepository = siteRepository;
        this.domainEventPublisher = domainEventPublisher;
    }

    @AuditLog(action = AuditLogAction.WORDPRESS_PROVISIONED, resourceType = "SITE")
    @Transactional
    public SiteResponse createManagedSite(CreateManagedWordPressSiteRequest request) {
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
            provisioningClient.deprovision(slug, dbName);
            throw e;
        }

        String internalUrl = "http://wordpress/sites/" + slug;
        Map<String, String> credentials = Map.of(
                "baseUrl", internalUrl,
                "username", result.adminUser(),
                "transport", "AGENT",
                "wpSlug", slug);

        SiteRegisterRequest registerRequest = new SiteRegisterRequest(
                request.name(), request.siteKey(), CmsType.WORDPRESS, credentials, null);

        SiteResponse response;
        try {
            response = siteService.register(registerRequest);
        } catch (RuntimeException e) {
            provisioningClient.deprovision(slug, dbName);
            throw e;
        }

        Site site = siteRepository.findBySiteKey(request.siteKey())
                .orElseThrow(() -> new SiteNotFoundException("siteKey '" + request.siteKey() + "' は登録されていません"));
        site.setManagedWordpress(true);
        site.setWpSlug(slug);
        site.setWpDbName(dbName);
        site.setBaseUrl(result.url());
        siteRepository.save(site);

        if (request.templateSiteId() != null) {
            cloneFromTemplate(request.templateSiteId(), site, slug, dbName);
        }

        return new SiteResponse(
                site.getId(), site.getName(), site.getSiteKey(), site.getCmsType(), site.getBaseUrl(),
                UtcDateTimes.toInstant(site.getCreatedAt()), UtcDateTimes.toInstant(site.getUpdatedAt()),
                response.connectionCheckStatus(), true, false,
                site.getAdminPath());
    }

    /**
     * {@link #createManagedSite}のジョブ用(issue #1479)。{@code @Async}のランナーから呼ぶ。
     *
     * <p>同期版との違いは2点。(1) <b>メソッド全体を{@code @Transactional}で包まない</b>。
     * 数分かかるprovision-agent呼び出しをトランザクションの中に置くと、バックグラウンドスレッドが
     * HikariCPの接続を掴み続ける({@code TermComparisonService}のjavadocが禁じる#1122〜#1124の障害)。
     * {@link SiteService#register}と{@code siteRepository}の呼び出しはそれぞれ短い自前の
     * トランザクションになる。(2) そのため同期版ではトランザクションのロールバックが担っていた
     * 登録済みサイトレコードの後始末を明示的に行う(登録後に失敗したらdeprovisionに加えてレコードも消す)。
     * 監査ログ({@code WORDPRESS_PROVISIONED})は同じ{@code @AuditLog}で残り、操作者は呼び出し側が
     * {@link CurrentActorService#runAs}で束縛したものが使われる。
     *
     * <p>同期版の本体は変えていない(リクエスト形式・応答・トランザクション境界・既存テストを保つため)。
     * 構築と登録の中身が重複するが、境界の異なる2経路を1つの本体へ畳むと同期版の挙動を変える
     * リスクがあるので、同期API廃止(#1478要件4)までは並べて持つ。
     *
     * @param phaseListener 進行段階({@code provisioning} → {@code registering})の通知先。nullなら通知しない。
     */
    @AuditLog(action = AuditLogAction.WORDPRESS_PROVISIONED, resourceType = "SITE")
    public SiteResponse createManagedSiteForJob(
            CreateManagedWordPressSiteRequest request, Consumer<String> phaseListener) {
        if (siteRepository.existsBySiteKey(request.siteKey())) {
            throw new DuplicateSiteKeyException("siteKey '" + request.siteKey() + "' は既に登録されています");
        }

        String slug = normalizeSlug(request.siteKey());
        String dbName = "wp_" + slug;
        String locale = (request.locale() != null && !request.locale().isBlank()) ? request.locale() : "ja";

        // 同期版と違い、複製元の存在は構築を始める前に確かめる。構築・登録の後で見つからないと、
        // コミット済みのサイトレコードとWordPressの実体が取り残される(cloneFromTemplateの
        // 後始末は「テンプレートが自動構築サイトでない」と複製失敗のときだけ)。
        if (request.templateSiteId() != null && siteRepository.findById(request.templateSiteId()).isEmpty()) {
            throw new SiteNotFoundException(
                    "id " + request.templateSiteId() + " のテンプレートサイトは登録されていません");
        }

        notifyPhase(phaseListener, "provisioning");
        WordPressProvisioningClient.ProvisionResult result;
        try {
            result = provisioningClient.provision(
                    new WordPressProvisioningClient.ProvisionCommand(
                            slug, dbName, request.title(), request.adminUser(), request.adminEmail(),
                            request.adminPassword(), locale));
        } catch (ProvisioningException e) {
            provisioningClient.deprovision(slug, dbName);
            throw e;
        }

        notifyPhase(phaseListener, "registering");
        Site site;
        SiteResponse response;
        try {
            Map<String, String> credentials = Map.of(
                    "baseUrl", "http://wordpress/sites/" + slug,
                    "username", result.adminUser(),
                    "transport", "AGENT",
                    "wpSlug", slug);
            response = siteService.register(new SiteRegisterRequest(
                    request.name(), request.siteKey(), CmsType.WORDPRESS, credentials, null));

            site = siteRepository.findBySiteKey(request.siteKey())
                    .orElseThrow(() -> new SiteNotFoundException(
                            "siteKey '" + request.siteKey() + "' は登録されていません"));
            site.setManagedWordpress(true);
            site.setWpSlug(slug);
            site.setWpDbName(dbName);
            site.setBaseUrl(result.url());
            siteRepository.save(site);
        } catch (RuntimeException e) {
            provisioningClient.deprovision(slug, dbName);
            // registerが自前のトランザクションごと戻した場合は何も残っていない。登録後に落ちた場合だけ消す。
            siteRepository.findBySiteKey(request.siteKey()).ifPresent(siteRepository::delete);
            throw e;
        }

        if (request.templateSiteId() != null) {
            // 複製に失敗したときのdeprovision・レコード削除は、同期版と共通のここが1度だけ行う。
            cloneFromTemplate(request.templateSiteId(), site, slug, dbName);
        }

        return new SiteResponse(
                site.getId(), site.getName(), site.getSiteKey(), site.getCmsType(), site.getBaseUrl(),
                UtcDateTimes.toInstant(site.getCreatedAt()), UtcDateTimes.toInstant(site.getUpdatedAt()),
                response.connectionCheckStatus(), true, false,
                site.getAdminPath());
    }

    private static void notifyPhase(Consumer<String> phaseListener, String phase) {
        if (phaseListener != null) {
            phaseListener.accept(phase);
        }
    }

    /**
     * DBには未登録だが、provisioning agent側には実体(ディレクトリ・DB)が既に存在する
     * WordPressサイトを取り込んで登録する(issue #317)。
     */
    @AuditLog(action = AuditLogAction.WORDPRESS_ADOPTED, resourceType = "SITE")
    @Transactional
    public SiteResponse adoptManagedSite(AdoptWordPressSiteRequest request) {
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
                "transport", "AGENT",
                "wpSlug", slug);

        SiteRegisterRequest registerRequest = new SiteRegisterRequest(
                request.name(), request.siteKey(), CmsType.WORDPRESS, credentials, null);

        SiteResponse response = siteService.register(registerRequest);

        Site site = siteRepository.findBySiteKey(request.siteKey())
                .orElseThrow(() -> new SiteNotFoundException("siteKey '" + request.siteKey() + "' は登録されていません"));

        // issue #1198 AC1: 同じ物理実体(同じ正規化wp_slug)を既に別のサイトレコードが参照している
        // 場合でも、adopt自体は拒否しない(site-adoption.featureのフィクスチャ手順がこの成功を
        // 前提にしているため。Out of Scope)。ただし「無条件で成功しただけ」にはせず、共有先の
        // 既存サイトを名指ししたWARNログを残し、運用者が意図的な共有状態だと確認できるようにする。
        findOtherSiteWithWpSlug(slug, site.getId()).ifPresent(existing -> log.warn(
                "adoptManagedSite: siteKey '{}' はwp_slug '{}' を既存のサイトid={}(siteKey={})と共有しています。"
                        + "同じ物理実体(ディレクトリ・DB)を複数のサイトレコードが参照する状態です(issue #1198)。",
                request.siteKey(), slug, existing.getId(), existing.getSiteKey()));

        site.setManagedWordpress(true);
        site.setWpSlug(slug);
        site.setWpDbName(dbName);
        site.setBaseUrl(result.url());
        siteRepository.save(site);

        return new SiteResponse(
                site.getId(), site.getName(), site.getSiteKey(), site.getCmsType(), site.getBaseUrl(),
                UtcDateTimes.toInstant(site.getCreatedAt()), UtcDateTimes.toInstant(site.getUpdatedAt()),
                response.connectionCheckStatus(), true, false,
                site.getAdminPath());
    }

    @AuditLog(action = AuditLogAction.SITE_DELETED, resourceType = "SITE")
    @Transactional
    public void deleteSite(Long siteId) {
        Site site = siteRepository.findById(siteId)
                .orElseThrow(() -> new SiteNotFoundException("id " + siteId + " のサイトは登録されていません"));

        if (site.isManagedWordpress() && findOtherSiteWithWpSlug(site.getWpSlug(), site.getId()).isEmpty()) {
            provisioningClient.deprovision(site.getWpSlug(), site.getWpDbName());
        }
        siteRepository.delete(site);
        // 投稿の削除はcontent-serviceへの内部ブリッジがまだ無いため同期では行わない(クラスjavadoc参照)。
        // site.deletedイベント(letsblog.events、issue #580)はcontent-service側が購読済みのため、
        // 非同期経路で投稿削除が行われる。
        domainEventPublisher.publishSiteDeleted(siteId);
    }

    /**
     * issue #1198: {@code adoptManagedSite}は、正規化後に同じ{@code wp_slug}(=同じ物理実体
     * ディレクトリ・DB)を指す複数のサイトレコードが存在すること自体は拒否しない
     * (site-adoption.featureのフィクスチャ手順が、この挙動を使って「provision-agent側には
     * 実体があるがDBには未登録」の状態を意図的に再現しているため。issue #1198のOut of Scope)。
     * そのため{@code deleteSite}では、自分以外に同じwp_slugを参照しているレコードがまだ残って
     * いるかをここで確認し、残っていれば物理的な実体の削除(deprovision)を見送る。DBレコード
     * 自体は通常どおり削除してよい(最後の参照が消えたときに、その削除で初めてdeprovisionされる)。
     * {@code adoptManagedSite}は、この結果を使って共有先の既存サイトをWARNログへ明示する。
     */
    private Optional<Site> findOtherSiteWithWpSlug(String wpSlug, Long excludingSiteId) {
        return siteRepository.findAll().stream()
                .filter(other -> !other.getId().equals(excludingSiteId) && wpSlug.equals(other.getWpSlug()))
                .findFirst();
    }

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
