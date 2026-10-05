package com.letsblog.project.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.letsblog.project.client.XApiClient;
import com.letsblog.project.client.XApiClient.XTokens;
import com.letsblog.project.client.XApiException;
import com.letsblog.project.cms.LetsblogPluginStatus;
import com.letsblog.project.domain.Project;
import com.letsblog.project.domain.Site;
import com.letsblog.project.dto.XConnectResult;
import com.letsblog.project.dto.XConnectionView;
import com.letsblog.project.dto.XTestResult;
import com.letsblog.project.repository.ProjectRepository;
import com.letsblog.project.repository.SiteRepository;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * プロジェクトの公式 X アカウントの接続(issue #1574。Epic #1572)。
 *
 * <ul>
 *   <li>接続先は、プロジェクトの<b>本番サイト</b>の letsblog プラグイン(導入済みであること。#1557)。認可で得たトークンは
 *       その場で {@code wp letsblog sns config set}(標準入力のJSON。#1573)でそのサイトへ送り、
 *       <b>アプリは保存しない・API で返さない</b>。クライアントの秘密と PKCE の検証子は認可の間だけメモリに持つ
 *       ({@link XAuthorizationStore})。送れなければ接続失敗。</li>
 *   <li>状態({@code status})と履歴({@code log})とテスト投稿({@code test})もすべて wp-cli。届かないときは
 *       「取得できない」として返し、例外にしない。</li>
 *   <li>本番サイトを変えたら({@link #onProductionSiteChanged})旧サイトの設定を消す(試みるだけ)。</li>
 * </ul>
 */
@Service
@Slf4j
public class SnsXService {

    private static final String SNS = "x";
    private static final String STATUS_CONNECTED = "接続済み";
    private static final String STATUS_UNSET = "未設定";
    private static final String STATUS_RECONNECT = "要再接続";
    private static final int LOG_LIMIT = 20;

    private final ProjectRepository projectRepository;
    private final SiteRepository siteRepository;
    private final SiteService siteService;
    private final XApiClient xApiClient;
    private final XAuthorizationStore store;
    private final CurrentActorService currentActorService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public SnsXService(
            ProjectRepository projectRepository,
            SiteRepository siteRepository,
            SiteService siteService,
            XApiClient xApiClient,
            XAuthorizationStore store,
            CurrentActorService currentActorService,
            ObjectMapper objectMapper) {
        this(projectRepository, siteRepository, siteService, xApiClient, store, currentActorService, objectMapper,
                Clock.systemUTC());
    }

    SnsXService(
            ProjectRepository projectRepository,
            SiteRepository siteRepository,
            SiteService siteService,
            XApiClient xApiClient,
            XAuthorizationStore store,
            CurrentActorService currentActorService,
            ObjectMapper objectMapper,
            Clock clock) {
        this.projectRepository = projectRepository;
        this.siteRepository = siteRepository;
        this.siteService = siteService;
        this.xApiClient = xApiClient;
        this.store = store;
        this.currentActorService = currentActorService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** 画面に出す接続状態。本番サイトに届かなくても例外にしない。 */
    public XConnectionView view(Long projectId) {
        Project project = findProject(projectId);
        Optional<Site> site = productionSite(project);
        if (site.isEmpty()) {
            return new XConnectionView(false, reasonNoSite(), null, null, null);
        }
        Long siteId = site.get().getId();
        String siteName = site.get().getName();
        LetsblogPluginStatus plugin;
        try {
            plugin = siteService.getLetsblogPluginStatus(siteId);
        } catch (RuntimeException e) {
            log.warn("本番サイトのプラグインの状態を取得できません (projectId={}, siteId={}): {}",
                    projectId, siteId, e.getMessage());
            String message = e.getMessage();
            return new XConnectionView(false, "本番サイトに届かないため、接続できません", siteName,
                    XConnectionView.Status.unavailable(message), XConnectionView.Log.unavailable(message));
        }
        if (plugin.state() != LetsblogPluginStatus.State.INSTALLED) {
            return new XConnectionView(false, reasonPlugin(plugin), siteName, null, null);
        }
        return new XConnectionView(true, null, siteName, readStatus(siteId), readLog(siteId));
    }

    /** 認可を始め、X の認可画面の URL を返す。接続できない状態では始めない。 */
    public String startAuthorization(Long projectId, String clientId, String clientSecret, String redirectUri) {
        if (isBlank(clientId) || isBlank(clientSecret) || isBlank(redirectUri)) {
            throw new IllegalArgumentException("クライアントID・クライアントシークレット・リダイレクト先は必須です");
        }
        Long siteId = requireConnectableSite(findProject(projectId));
        String actorSub = currentActorService.getCurrentActorKeycloakSub();
        XAuthorizationStore.Pending pending =
                store.create(projectId, siteId, actorSub, clientId, clientSecret, redirectUri);
        return xApiClient.authorizeUrl(clientId, redirectUri, pending.state(), pending.codeChallenge());
    }

    /**
     * X の認可画面から戻ったときの処理。認可コードをトークンに交換し、本番サイトへ送る。
     * 認可を始めた本人・同じプロジェクトでなければ拒否する。
     */
    public XConnectResult completeAuthorization(Long projectId, String state, String code) {
        String invalid = "認可の有効期限が切れたか、リクエストが不正です。もう一度接続してください";
        String actorSub = currentActorService.getCurrentActorKeycloakSub();
        XAuthorizationStore.Pending pending = store.take(state)
                .filter(p -> p.projectId().equals(projectId))
                .filter(p -> p.actorSub() != null && p.actorSub().equals(actorSub))
                .orElseThrow(() -> new IllegalArgumentException(invalid));
        Long siteId = requireConnectableSite(findProject(projectId));

        XTokens tokens;
        String accountName;
        try {
            tokens = xApiClient.exchangeCode(
                    pending.clientId(), pending.clientSecret(), code, pending.redirectUri(), pending.codeVerifier());
            accountName = xApiClient.fetchUsername(tokens.accessToken());
        } catch (XApiException e) {
            throw new IllegalStateException("X との認可に失敗しました: " + e.getMessage(), e);
        }

        String stdin = configSetPayload(pending, tokens, accountName);
        String stdout;
        try {
            stdout = siteService.runLetsblogSns(siteId, "config-set", SNS, stdin);
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "本番サイトへトークンを送れませんでした(接続失敗): " + e.getMessage(), e);
        }
        if (!STATUS_CONNECTED.equals(readConfigSetStatus(stdout))) {
            throw new IllegalStateException("本番サイトのプラグインが接続済みになりませんでした(接続失敗)");
        }
        return new XConnectResult(projectId, accountName);
    }

    /** テスト投稿。失敗してもプラグインが履歴に残すので、理由を返すだけ。 */
    public XTestResult test(Long projectId) {
        Long siteId = requireConnectableSite(findProject(projectId));
        try {
            siteService.runLetsblogSns(siteId, "test", SNS, null);
            return new XTestResult(true, null);
        } catch (RuntimeException e) {
            return new XTestResult(false, e.getMessage());
        }
    }

    /**
     * プロジェクトの本番サイトが変わった(別のサイトへ付け替え・外した)。旧サイトの X の設定を消す。
     * 旧サイトに届かなくても本番サイトの変更は止めない(試みるだけ)。新サイトは設定が無いので未接続になる。
     */
    public void onProductionSiteChanged(Long previousSiteId, Long newSiteId) {
        if (previousSiteId == null || previousSiteId.equals(newSiteId)) {
            return;
        }
        try {
            siteService.runLetsblogSns(previousSiteId, "config-clear", null, null);
        } catch (RuntimeException e) {
            log.warn("旧い本番サイトの SNS 設定を消せませんでした (siteId={}): {}", previousSiteId, e.getMessage());
        }
    }

    private Project findProject(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ProjectNotFoundException("id " + projectId + " のプロジェクトは登録されていません"));
    }

    private Optional<Site> productionSite(Project project) {
        Long siteId = project.getProductionSiteId();
        return siteId == null ? Optional.empty() : siteRepository.findById(siteId);
    }

    /** 接続操作ができる本番サイトのID。できないなら理由つきで例外。 */
    private Long requireConnectableSite(Project project) {
        Optional<Site> site = productionSite(project);
        if (site.isEmpty()) {
            throw new IllegalStateException(reasonNoSite());
        }
        LetsblogPluginStatus plugin;
        try {
            plugin = siteService.getLetsblogPluginStatus(site.get().getId());
        } catch (RuntimeException e) {
            throw new IllegalStateException("本番サイトに届かないため、接続できません: " + e.getMessage(), e);
        }
        if (plugin.state() != LetsblogPluginStatus.State.INSTALLED) {
            throw new IllegalStateException(reasonPlugin(plugin));
        }
        return site.get().getId();
    }

    private static String reasonNoSite() {
        return "本番サイトが設定されていません。プロジェクトの環境設定で本番サイトを設定してください";
    }

    private static String reasonPlugin(LetsblogPluginStatus plugin) {
        String label = plugin.state() == LetsblogPluginStatus.State.NEEDS_UPDATE ? "要更新" : "未導入";
        return "本番サイトの letsblog プラグインが" + label + "です。サイト編集画面でプラグインを再導入してください";
    }

    private String configSetPayload(XAuthorizationStore.Pending pending, XTokens tokens, String accountName) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("sns", SNS);
        node.put("client_id", pending.clientId());
        node.put("client_secret", pending.clientSecret());
        node.put("access_token", tokens.accessToken());
        node.put("refresh_token", tokens.refreshToken());
        node.put("expires_at", clock.instant().getEpochSecond() + tokens.expiresIn());
        node.put("account_name", accountName);
        return node.toString();
    }

    private String readConfigSetStatus(String stdout) {
        try {
            return objectMapper.readTree(stdout).path("status").asText(null);
        } catch (JsonProcessingException | RuntimeException e) {
            throw new IllegalStateException("本番サイトのプラグインの出力を解釈できません(接続失敗)", e);
        }
    }

    private XConnectionView.Status readStatus(Long siteId) {
        try {
            JsonNode x = objectMapper.readTree(siteService.runLetsblogSns(siteId, "status", SNS, null)).path(SNS);
            XConnectionView.State state = switch (x.path("status").asText("")) {
                case STATUS_CONNECTED -> XConnectionView.State.CONNECTED;
                case STATUS_UNSET -> XConnectionView.State.UNSET;
                case STATUS_RECONNECT -> XConnectionView.State.RECONNECT;
                default -> throw new IllegalStateException("接続状態を解釈できません");
            };
            return new XConnectionView.Status(true, state, x.path("account_name").asText(null), null);
        } catch (JsonProcessingException | RuntimeException e) {
            return XConnectionView.Status.unavailable(e.getMessage());
        }
    }

    private XConnectionView.Log readLog(Long siteId) {
        try {
            JsonNode all = objectMapper.readTree(siteService.runLetsblogSns(siteId, "log", SNS, null));
            if (!all.isArray()) {
                throw new IllegalStateException("告知履歴を解釈できません");
            }
            List<XConnectionView.Entry> entries = new ArrayList<>();
            for (JsonNode item : all) {
                if (SNS.equals(item.path("sns").asText())) {
                    entries.add(new XConnectionView.Entry(
                            item.path("kind").asText(), item.path("success").asBoolean(false),
                            item.path("error").asText(null), item.path("at").asText(null)));
                }
            }
            // プラグインは古い順に記録する。画面は新しい順で、直近の LOG_LIMIT 件。
            java.util.Collections.reverse(entries);
            return new XConnectionView.Log(true, entries.stream().limit(LOG_LIMIT).toList(), null);
        } catch (JsonProcessingException | RuntimeException e) {
            return XConnectionView.Log.unavailable(e.getMessage());
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
