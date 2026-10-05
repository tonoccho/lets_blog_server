package com.letsblog.project.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.letsblog.project.client.AnalyticsBridgeClient;
import com.letsblog.project.client.AnalyticsBridgeClient.GoogleAnalyticsCredentials;
import com.letsblog.project.cms.LetsblogPluginStatus;
import com.letsblog.project.domain.Project;
import com.letsblog.project.domain.ProjectPvRule;
import com.letsblog.project.domain.ProjectPvSyncState;
import com.letsblog.project.domain.Site;
import com.letsblog.project.dto.PvRulesView;
import com.letsblog.project.repository.ProjectPvRuleRepository;
import com.letsblog.project.repository.ProjectPvSyncStateRepository;
import com.letsblog.project.repository.ProjectRepository;
import com.letsblog.project.repository.SiteRepository;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * プロジェクトの PV 達成ルール(issue #1578。Epic #1572)。
 *
 * <ul>
 *   <li>ルールの<b>正本はアプリ</b>({@code project_pv_rules})。本番サイトのプラグインへは、ルールを保存・削除したとき、
 *       GA を連携したとき、画面から再送したときに、GA4 の認証情報({@code wp letsblog pv config set})とルール全件
 *       ({@code wp letsblog pv rules set}。プラグインは丸ごと置き換える)を wp-cli で送る。
 *       認証情報は analytics-service から復号済みで読み、標準入力で送るだけで、アプリは保存しない。</li>
 *   <li>GA が未連携のプロジェクトではルールを追加できない(理由を返す)。</li>
 *   <li>本番サイトへ送れなかったときは、ルールは保存したまま「送信失敗」として記録する(例外にしない)。
 *       再送({@link #resend})で回復できる。記録する理由に GA4 の認証情報は含めない。</li>
 * </ul>
 *
 * <p>リモート呼び出し(analytics-service・本番サイト)はトランザクションの外で行う(リポジトリの呼び出しごとに完結する)。
 */
@Service
@Slf4j
public class PvRuleService {

    private static final String PERIOD_DAILY = "daily";
    private static final String PERIOD_TOTAL = "total";
    private static final String STATE_NONE = "NONE";
    private static final String STATE_SENT = "SENT";
    private static final String STATE_FAILED = "FAILED";
    private static final String STATUS_CONNECTED = "接続済み";
    private static final Pattern RULE_ID = Pattern.compile("^r(\\d{1,18})$");
    private static final String REASON_GA_UNLINKED =
            "GA が連携されていません。プロジェクトの Google Analytics 設定で連携してから、ルールを追加してください";

    private final ProjectRepository projectRepository;
    private final SiteRepository siteRepository;
    private final SiteService siteService;
    private final AnalyticsBridgeClient analyticsBridgeClient;
    private final ProjectPvRuleRepository ruleRepository;
    private final ProjectPvSyncStateRepository syncRepository;
    private final CurrentActorService currentActorService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public PvRuleService(
            ProjectRepository projectRepository,
            SiteRepository siteRepository,
            SiteService siteService,
            AnalyticsBridgeClient analyticsBridgeClient,
            ProjectPvRuleRepository ruleRepository,
            ProjectPvSyncStateRepository syncRepository,
            CurrentActorService currentActorService,
            ObjectMapper objectMapper) {
        this(projectRepository, siteRepository, siteService, analyticsBridgeClient, ruleRepository, syncRepository,
                currentActorService, objectMapper, Clock.systemUTC());
    }

    PvRuleService(
            ProjectRepository projectRepository,
            SiteRepository siteRepository,
            SiteService siteService,
            AnalyticsBridgeClient analyticsBridgeClient,
            ProjectPvRuleRepository ruleRepository,
            ProjectPvSyncStateRepository syncRepository,
            CurrentActorService currentActorService,
            ObjectMapper objectMapper,
            Clock clock) {
        this.projectRepository = projectRepository;
        this.siteRepository = siteRepository;
        this.siteService = siteService;
        this.analyticsBridgeClient = analyticsBridgeClient;
        this.ruleRepository = ruleRepository;
        this.syncRepository = syncRepository;
        this.currentActorService = currentActorService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** 画面に出すルールと送信状態。GA の連携状態を取れなくても例外にしない(追加できないとして理由を返す)。 */
    public PvRulesView view(Long projectId) {
        findProject(projectId);
        String reason;
        try {
            reason = analyticsBridgeClient.googleAnalyticsCredentials(projectId, bearer()).configured()
                    ? null : REASON_GA_UNLINKED;
        } catch (RuntimeException e) {
            log.warn("GA の連携状態を取得できません (projectId={}): {}", projectId, e.getMessage());
            reason = "GA の連携状態を取得できません: " + e.getMessage();
        }
        return buildView(projectId, reason);
    }

    /** ルールを追加し、本番サイトへ送る。GA が未連携なら保存せずに拒否する。送れなくても保存は残り、送信失敗になる。 */
    public PvRulesView addRule(Long projectId, String period, int threshold) {
        findProject(projectId);
        if (!PERIOD_DAILY.equals(period) && !PERIOD_TOTAL.equals(period)) {
            throw new IllegalArgumentException("期間は 1日(daily)か 累計(total)を指定してください");
        }
        if (threshold < 1) {
            throw new IllegalArgumentException("閾値は 1 以上の整数で指定してください");
        }
        requireGaLinked(projectId);

        ProjectPvRule rule = new ProjectPvRule();
        rule.setProjectId(projectId);
        rule.setPeriod(period);
        rule.setThreshold(threshold);
        ruleRepository.save(rule);

        sync(projectId);
        return buildView(projectId, null);
    }

    /** ルールを削除し、残りのルール全件を本番サイトへ送る(プラグインはルールを丸ごと置き換える)。 */
    public PvRulesView deleteRule(Long projectId, String ruleId) {
        findProject(projectId);
        ProjectPvRule rule = parseRuleId(ruleId)
                .flatMap(id -> ruleRepository.findByIdAndProjectId(id, projectId))
                .orElseThrow(() -> new IllegalArgumentException("ルールが見つかりません"));
        ruleRepository.delete(rule);

        sync(projectId);
        return view(projectId);
    }

    /** 送信に失敗したときの再送。GA4 の認証情報とルール全件を送り直す。 */
    public PvRulesView resend(Long projectId) {
        findProject(projectId);
        sync(projectId);
        return view(projectId);
    }

    /** GA のプロパティを選び終えた(連携が完了した)とき。ルールが無くても GA4 の認証情報は送る。失敗は記録するだけ。 */
    public void onGoogleAnalyticsConnected(Long projectId) {
        findProject(projectId);
        sync(projectId);
    }

    private void requireGaLinked(Long projectId) {
        boolean configured;
        try {
            configured = analyticsBridgeClient.googleAnalyticsCredentials(projectId, bearer()).configured();
        } catch (RuntimeException e) {
            throw new IllegalStateException("GA の連携状態を取得できません: " + e.getMessage(), e);
        }
        if (!configured) {
            throw new IllegalStateException(REASON_GA_UNLINKED);
        }
    }

    /** 送信し、結果(成功・失敗と理由)を記録する。例外にはしない。 */
    private void sync(Long projectId) {
        String failure = trySend(projectId);
        ProjectPvSyncState state = syncRepository.findById(projectId).orElseGet(ProjectPvSyncState::new);
        state.setProjectId(projectId);
        state.setState(failure == null ? STATE_SENT : STATE_FAILED);
        state.setError(failure);
        state.setSentAt(clock.instant());
        syncRepository.save(state);
    }

    /** 本番サイトへ送る。成功なら null、失敗なら理由(GA4 の認証情報を含めない)。 */
    private String trySend(Long projectId) {
        Project project = findProject(projectId);
        Optional<Site> site = project.getProductionSiteId() == null
                ? Optional.empty() : siteRepository.findById(project.getProductionSiteId());
        if (site.isEmpty()) {
            return "本番サイトが設定されていません。プロジェクトの環境設定で本番サイトを設定してください";
        }
        Long siteId = site.get().getId();
        LetsblogPluginStatus plugin;
        try {
            plugin = siteService.getLetsblogPluginStatus(siteId);
        } catch (RuntimeException e) {
            return "本番サイトに届かないため、送信できません: " + e.getMessage();
        }
        if (plugin.state() != LetsblogPluginStatus.State.INSTALLED) {
            String label = plugin.state() == LetsblogPluginStatus.State.NEEDS_UPDATE ? "要更新" : "未導入";
            return "本番サイトの letsblog プラグインが" + label + "です。サイト編集画面でプラグインを再導入してください";
        }
        GoogleAnalyticsCredentials credentials;
        try {
            credentials = analyticsBridgeClient.googleAnalyticsCredentials(projectId, bearer());
        } catch (RuntimeException e) {
            return "GA の認証情報を取得できません: " + e.getMessage();
        }
        if (!credentials.configured()) {
            return REASON_GA_UNLINKED;
        }
        try {
            siteService.runLetsblogSns(siteId, "pv-config-set", null, configPayload(credentials));
            if (!connected(siteService.runLetsblogSns(siteId, "pv-status", null, null))) {
                return "本番サイトのプラグインが GA の接続済みになりませんでした";
            }
            siteService.runLetsblogSns(siteId, "pv-rules-set", null, rulesPayload(projectId));
            return null;
        } catch (RuntimeException e) {
            log.warn("PV 達成ルールを本番サイトへ送れませんでした (projectId={}, siteId={}): {}",
                    projectId, siteId, e.getMessage());
            return "本番サイトへ送れませんでした: " + e.getMessage();
        }
    }

    private String configPayload(GoogleAnalyticsCredentials credentials) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("property_id", credentials.propertyId());
        node.put("client_id", credentials.clientId());
        node.put("client_secret", credentials.clientSecret());
        node.put("refresh_token", credentials.refreshToken());
        return node.toString();
    }

    private String rulesPayload(Long projectId) {
        ArrayNode rules = objectMapper.createArrayNode();
        for (ProjectPvRule rule : ruleRepository.findByProjectIdOrderByIdAsc(projectId)) {
            ObjectNode node = rules.addObject();
            node.put("id", ruleId(rule));
            node.put("period", rule.getPeriod());
            node.put("threshold", rule.getThreshold());
        }
        return rules.toString();
    }

    /** `wp letsblog pv status` が「設定済み(configured: true)かつ接続済み」を示しているか。解釈できなければ false。 */
    private boolean connected(String stdout) {
        try {
            JsonNode status = objectMapper.readTree(stdout);
            return status.path("configured").asBoolean(false)
                    && STATUS_CONNECTED.equals(status.path("state").asText(null));
        } catch (JsonProcessingException e) {
            return false;
        }
    }

    private PvRulesView buildView(Long projectId, String reason) {
        List<PvRulesView.Rule> rules = ruleRepository.findByProjectIdOrderByIdAsc(projectId).stream()
                .map(rule -> new PvRulesView.Rule(ruleId(rule), rule.getPeriod(), rule.getThreshold()))
                .toList();
        PvRulesView.Send send = syncRepository.findById(projectId)
                .map(state -> new PvRulesView.Send(state.getState(), state.getError(),
                        state.getSentAt() == null ? null : state.getSentAt().toString()))
                .orElseGet(() -> new PvRulesView.Send(STATE_NONE, null, null));
        return new PvRulesView(reason == null, reason, rules, send);
    }

    private static String ruleId(ProjectPvRule rule) {
        return "r" + rule.getId();
    }

    private static Optional<Long> parseRuleId(String ruleId) {
        if (ruleId == null) {
            return Optional.empty();
        }
        Matcher matcher = RULE_ID.matcher(ruleId);
        return matcher.matches() ? Optional.of(Long.valueOf(matcher.group(1))) : Optional.empty();
    }

    private Project findProject(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ProjectNotFoundException("id " + projectId + " のプロジェクトは登録されていません"));
    }

    private String bearer() {
        return currentActorService.getAuthorizationHeader();
    }
}
