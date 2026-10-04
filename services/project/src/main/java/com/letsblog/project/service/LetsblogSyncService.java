package com.letsblog.project.service;

import com.letsblog.project.client.BearerScope;
import com.letsblog.project.client.ContentBridgeClient;
import com.letsblog.project.client.ContentBridgeClient.SyncPayload;
import com.letsblog.project.cms.LetsblogPluginStatus;
import com.letsblog.project.domain.LetsblogSyncStatus;
import com.letsblog.project.domain.Project;
import com.letsblog.project.domain.Site;
import com.letsblog.project.dto.LetsblogSyncState;
import com.letsblog.project.repository.ProjectRepository;
import com.letsblog.project.repository.SiteRepository;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * タグ定義・統合CSS・プレフィックス・組み込みタグのデザインを、WordPress の letsblog プラグインへ送る
 * (issue #1558)。内容は content-service が組み立てる({@link ContentBridgeClient})。送信は wp-cli だけで行い、
 * 自動構築サイトは provision-agent、SSH サイトは SSH 経由({@link SiteService#syncLetsblogPlugin})。
 *
 * <ul>
 *   <li>プロジェクトの変更はそのプロジェクトのすべてのサイト(local/test/production)へ、グローバルタグの
 *       変更はすべてのプロジェクトのサイトへ送る。</li>
 *   <li>プラグインが未導入・要更新(#1557)のサイトへは送らず、見送りとして記録する。</li>
 *   <li>届かなかったサイトは失敗として記録し、サイト画面に出す。再同期(手動)で回復する。</li>
 *   <li>依頼({@code request*})は保存の応答を待たせないよう、1本のスレッドで順に実行する。順に実行するので、
 *       最後の依頼が最後に反映される。</li>
 * </ul>
 */
@Service
@Slf4j
public class LetsblogSyncService {

    private final SiteService siteService;
    private final SiteRepository siteRepository;
    private final ProjectRepository projectRepository;
    private final ContentBridgeClient contentBridgeClient;
    private final CurrentActorService currentActorService;
    private final Executor executor;

    public LetsblogSyncService(
            SiteService siteService,
            SiteRepository siteRepository,
            ProjectRepository projectRepository,
            ContentBridgeClient contentBridgeClient,
            CurrentActorService currentActorService,
            @Qualifier("letsblogSyncExecutor") Executor executor) {
        this.siteService = siteService;
        this.siteRepository = siteRepository;
        this.projectRepository = projectRepository;
        this.contentBridgeClient = contentBridgeClient;
        this.currentActorService = currentActorService;
        this.executor = executor;
    }

    /** そのプロジェクトのすべてのサイトへの同期を依頼する(応答を待たせない)。呼び出し元のトークンを取り置く。 */
    public void requestProjectSync(Long projectId) {
        String bearer = currentActorService.getAuthorizationHeader();
        executor.execute(() -> runSafely(() -> syncProject(projectId, bearer)));
    }

    /** すべてのプロジェクトのサイトへの同期を依頼する(グローバルタグ・デザインの変更)。 */
    public void requestAllSync() {
        String bearer = currentActorService.getAuthorizationHeader();
        executor.execute(() -> runSafely(() -> syncAll(bearer)));
    }

    public void syncAll(String bearer) {
        for (Project project : projectRepository.findAll()) {
            runSafely(() -> syncProject(project.getId(), bearer));
        }
    }

    public void syncProject(Long projectId, String bearer) {
        Optional<Project> project = projectRepository.findById(projectId);
        if (project.isEmpty()) {
            return;
        }
        List<Site> sites = targetSites(project.get());
        if (sites.isEmpty()) {
            return;
        }
        SyncPayload payload;
        try {
            payload = BearerScope.call(bearer, () -> contentBridgeClient.fetchSyncPayload(projectId, bearer));
        } catch (RuntimeException e) {
            for (Site site : sites) {
                record(site, LetsblogSyncStatus.FAILED, "同期内容を取得できませんでした: " + e.getMessage(), null);
            }
            return;
        }
        for (Site site : sites) {
            BearerScope.call(bearer, () -> {
                syncOne(site, payload);
                return null;
            });
        }
    }

    /** 手動の再同期。そのサイトが属するプロジェクトの内容を、その場で送って結果の状態を返す。 */
    public LetsblogSyncState syncSiteNow(Long siteId) {
        String bearer = currentActorService.getAuthorizationHeader();
        Site site = findSite(siteId);
        Optional<Project> project = projectRepository.findByLocalSiteIdOrTestSiteIdOrProductionSiteId(
                siteId, siteId, siteId);
        if (project.isEmpty()) {
            record(site, LetsblogSyncStatus.SKIPPED, "プロジェクトに紐付いていないため同期しません", null);
            return LetsblogSyncState.from(site);
        }
        SyncPayload payload;
        try {
            payload = BearerScope.call(
                    bearer, () -> contentBridgeClient.fetchSyncPayload(project.get().getId(), bearer));
        } catch (RuntimeException e) {
            record(site, LetsblogSyncStatus.FAILED, "同期内容を取得できませんでした: " + e.getMessage(), null);
            return LetsblogSyncState.from(site);
        }
        BearerScope.call(bearer, () -> {
            syncOne(site, payload);
            return null;
        });
        return LetsblogSyncState.from(site);
    }

    public LetsblogSyncState getState(Long siteId) {
        return LetsblogSyncState.from(findSite(siteId));
    }

    private Site findSite(Long siteId) {
        return siteRepository.findById(siteId)
                .orElseThrow(() -> new SiteNotFoundException("id " + siteId + " のサイトは登録されていません"));
    }

    /** プロジェクトの local/test/production のサイト。未設定・重複・存在しないものは除く。 */
    private List<Site> targetSites(Project project) {
        Set<Long> ids = new LinkedHashSet<>();
        for (Long id : new Long[] {project.getLocalSiteId(), project.getTestSiteId(), project.getProductionSiteId()}) {
            if (id != null) {
                ids.add(id);
            }
        }
        return ids.stream()
                .map(siteRepository::findById)
                .flatMap(Optional::stream)
                .toList();
    }

    private void syncOne(Site site, SyncPayload payload) {
        LetsblogPluginStatus status;
        try {
            status = siteService.getLetsblogPluginStatus(site.getId());
        } catch (RuntimeException e) {
            record(site, LetsblogSyncStatus.FAILED, "プラグインの状態を取得できませんでした: " + e.getMessage(), null);
            return;
        }
        if (status.state() != LetsblogPluginStatus.State.INSTALLED) {
            String label = status.state() == LetsblogPluginStatus.State.NEEDS_UPDATE ? "要更新" : "未導入";
            record(site, LetsblogSyncStatus.SKIPPED,
                    "プラグインが" + label + "のため送信しませんでした。サイト画面で再導入してください", null);
            return;
        }
        try {
            String savedHash = siteService.syncLetsblogPlugin(site.getId(), payload.payload(), payload.hash());
            if (!payload.hash().equals(savedHash)) {
                record(site, LetsblogSyncStatus.FAILED,
                        "プラグインが保存したハッシュ(" + savedHash + ")が送った内容のハッシュ(" + payload.hash()
                                + ")と一致しません", null);
                return;
            }
            record(site, LetsblogSyncStatus.SYNCED, null, savedHash);
        } catch (RuntimeException e) {
            record(site, LetsblogSyncStatus.FAILED, "送信に失敗しました: " + e.getMessage(), null);
        }
    }

    private void record(Site site, LetsblogSyncStatus status, String error, String hash) {
        site.setLetsblogSyncStatus(status);
        site.setLetsblogSyncError(error);
        site.setLetsblogSyncHash(hash);
        site.setLetsblogSyncedAt(LocalDateTime.now());
        siteRepository.save(site);
    }

    private void runSafely(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("letsblogプラグインへの同期中に予期しないエラーが発生しました", e);
        }
    }
}
