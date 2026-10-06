package com.letsblog.project.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.letsblog.project.cms.LetsblogPluginStatus;
import com.letsblog.project.domain.Project;
import com.letsblog.project.domain.ProjectSnsTemplate;
import com.letsblog.project.domain.Site;
import com.letsblog.project.dto.SnsTemplatesView;
import com.letsblog.project.repository.ProjectRepository;
import com.letsblog.project.repository.ProjectSnsTemplateRepository;
import com.letsblog.project.repository.SiteRepository;
import java.time.Clock;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * プロジェクトの告知文テンプレート(issue #1583。Epic #1572)。
 *
 * <ul>
 *   <li>公開時と PV 達成時のテンプレートを別々に持つ。<b>正本はアプリ</b>({@code project_sns_templates})で、
 *       保存したとき・画面から再送したときに、本番サイトのプラグインへ 2 つをまとめて
 *       {@code wp letsblog sns templates set}(標準入力の JSON。プラグインは丸ごと置き換える)で送る。</li>
 *   <li>空は「既定の告知文を使う」の意味で、空へ戻したときも本番サイトへ送る(プラグインの保存値を空にするため)。</li>
 *   <li>差し込み項目は {@code {title}}・{@code {url}}、PV 達成時のみ {@code {period}}・{@code {threshold}}。
 *       公開時のテンプレートに {@code {period}}・{@code {threshold}} を書くと、値が入らないので拒否する。</li>
 *   <li>本番サイトへ送れなかったときは、保存したまま「送信失敗」として記録する(例外にしない)。再送で回復できる。</li>
 * </ul>
 *
 * <p>リモート呼び出し(本番サイト)はトランザクションの外で行う(リポジトリの呼び出しごとに完結する)。
 */
@Service
@Slf4j
public class SnsTemplateService {

    /** テンプレート1つあたりの文字数の上限。プラグインも同じ上限で検証する。 */
    static final int TEMPLATE_LIMIT = 1000;

    private static final String STATE_NONE = "NONE";
    private static final String STATE_SENT = "SENT";
    private static final String STATE_FAILED = "FAILED";

    private final ProjectRepository projectRepository;
    private final SiteRepository siteRepository;
    private final SiteService siteService;
    private final ProjectSnsTemplateRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public SnsTemplateService(
            ProjectRepository projectRepository,
            SiteRepository siteRepository,
            SiteService siteService,
            ProjectSnsTemplateRepository repository,
            ObjectMapper objectMapper) {
        this(projectRepository, siteRepository, siteService, repository, objectMapper, Clock.systemUTC());
    }

    SnsTemplateService(
            ProjectRepository projectRepository,
            SiteRepository siteRepository,
            SiteService siteService,
            ProjectSnsTemplateRepository repository,
            ObjectMapper objectMapper,
            Clock clock) {
        this.projectRepository = projectRepository;
        this.siteRepository = siteRepository;
        this.siteService = siteService;
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** 画面に出すテンプレートと送信状態。まだ保存していなければ空のテンプレートと「まだ送っていない」。 */
    public SnsTemplatesView view(Long projectId) {
        findProject(projectId);
        return toView(repository.findById(projectId).orElse(null));
    }

    /** テンプレートを検証して保存し、本番サイトへ送る。送れなくても保存は残り、送信失敗になる。 */
    public SnsTemplatesView save(Long projectId, String publishTemplate, String pvTemplate) {
        findProject(projectId);
        String publish = publishTemplate == null ? "" : publishTemplate;
        String pv = pvTemplate == null ? "" : pvTemplate;
        validate(publish, pv);

        ProjectSnsTemplate row = repository.findById(projectId).orElseGet(ProjectSnsTemplate::new);
        row.setProjectId(projectId);
        row.setPublishTemplate(publish);
        row.setPvTemplate(pv);
        return sync(row);
    }

    /** 送信に失敗したときの再送。保存済みのテンプレートを送り直す(まだ保存していなければ空を送る)。 */
    public SnsTemplatesView resend(Long projectId) {
        findProject(projectId);
        ProjectSnsTemplate row = repository.findById(projectId).orElseGet(() -> {
            ProjectSnsTemplate empty = new ProjectSnsTemplate();
            empty.setProjectId(projectId);
            return empty;
        });
        return sync(row);
    }

    private void validate(String publish, String pv) {
        if (publish.length() > TEMPLATE_LIMIT) {
            throw new IllegalArgumentException("公開時のテンプレートは " + TEMPLATE_LIMIT + " 文字以内にしてください");
        }
        if (pv.length() > TEMPLATE_LIMIT) {
            throw new IllegalArgumentException("PV 達成時のテンプレートは " + TEMPLATE_LIMIT + " 文字以内にしてください");
        }
        for (String placeholder : new String[] {"{period}", "{threshold}"}) {
            if (publish.contains(placeholder)) {
                throw new IllegalArgumentException(
                        "公開時のテンプレートに " + placeholder + " は使えません(PV 達成時のテンプレートだけで使えます)");
            }
        }
    }

    /** 送信し、結果(成功・失敗と理由)を行へ記録して保存する。例外にはしない。 */
    private SnsTemplatesView sync(ProjectSnsTemplate row) {
        String failure = trySend(row);
        row.setSyncState(failure == null ? STATE_SENT : STATE_FAILED);
        row.setSyncError(failure);
        row.setSentAt(clock.instant());
        return toView(repository.save(row));
    }

    /** 本番サイトへ送る。成功なら null、失敗なら理由。 */
    private String trySend(ProjectSnsTemplate row) {
        Project project = findProject(row.getProjectId());
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
        try {
            siteService.runLetsblogSns(siteId, "templates-set", null, payload(row));
            return null;
        } catch (RuntimeException e) {
            log.warn("告知文テンプレートを本番サイトへ送れませんでした (projectId={}, siteId={}): {}",
                    row.getProjectId(), siteId, e.getMessage());
            return "本番サイトへ送れませんでした: " + e.getMessage();
        }
    }

    private String payload(ProjectSnsTemplate row) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("publish", row.getPublishTemplate());
        node.put("pv", row.getPvTemplate());
        return node.toString();
    }

    private SnsTemplatesView toView(ProjectSnsTemplate row) {
        if (row == null) {
            return new SnsTemplatesView("", "", new SnsTemplatesView.Send(STATE_NONE, null, null));
        }
        SnsTemplatesView.Send send = row.getSyncState() == null
                ? new SnsTemplatesView.Send(STATE_NONE, null, null)
                : new SnsTemplatesView.Send(row.getSyncState(), row.getSyncError(),
                        row.getSentAt() == null ? null : row.getSentAt().toString());
        return new SnsTemplatesView(row.getPublishTemplate(), row.getPvTemplate(), send);
    }

    private Project findProject(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ProjectNotFoundException("id " + projectId + " のプロジェクトは登録されていません"));
    }
}
