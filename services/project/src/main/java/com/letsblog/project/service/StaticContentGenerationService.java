package com.letsblog.project.service;

import com.letsblog.project.client.AiGenerationClient;
import com.letsblog.project.client.CmsProvisioningBridgeClient;
import com.letsblog.project.domain.Site;
import com.letsblog.project.domain.StaticContent;
import com.letsblog.project.domain.StaticContentType;
import com.letsblog.project.dto.StaticContentResponse;
import com.letsblog.project.provisioning.WordPressAgentPluginsClient;
import com.letsblog.project.repository.SiteRepository;
import com.letsblog.project.repository.StaticContentRepository;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * サイトに導入済みのプラグインを確認したうえで、LLM({@link AiGenerationClient}経由でai-serviceへ委譲)を
 * 使ってプライバシーポリシー・運営者情報をコピペ可能なテキストとして生成し、静的コンテンツとして保存する
 * (issue #577 stage2、legacy-apiから完全移設。static_contentテーブルの唯一の書き込み元)。
 */
@Service
public class StaticContentGenerationService {

    private static final Pattern TEXT_PATTERN = Pattern.compile("```text\\s*\\n([\\s\\S]*?)\\n```");

    private final SiteRepository siteRepository;
    private final SiteService siteService;
    private final WordPressAgentPluginsClient agentPluginsClient;
    private final CmsProvisioningBridgeClient bridgeClient;
    private final AiGenerationClient aiGenerationClient;
    private final StaticContentRepository staticContentRepository;

    public StaticContentGenerationService(
            SiteRepository siteRepository,
            SiteService siteService,
            WordPressAgentPluginsClient agentPluginsClient,
            CmsProvisioningBridgeClient bridgeClient,
            AiGenerationClient aiGenerationClient,
            StaticContentRepository staticContentRepository) {
        this.siteRepository = siteRepository;
        this.siteService = siteService;
        this.agentPluginsClient = agentPluginsClient;
        this.bridgeClient = bridgeClient;
        this.aiGenerationClient = aiGenerationClient;
        this.staticContentRepository = staticContentRepository;
    }

    @Transactional(readOnly = true)
    public List<StaticContentResponse> listBySite(Long siteId) {
        return staticContentRepository.findBySiteId(siteId).stream()
                .map(StaticContentResponse::from)
                .toList();
    }

    @Transactional
    public StaticContentResponse generate(Long siteId, StaticContentType contentType) {
        return upsert(siteId, contentType, generateBody(siteId, contentType));
    }

    /**
     * サイトのプラグイン構成からLLMで本文を生成するところまで。{@code static_content}へは書かない
     * (issue #1409)。同期の{@link #generate}(生成と同時に保存する)と、非同期ジョブ
     * ({@code TextGenerationJobRunner})が共有する。非同期ジョブの結果は{@code generation_jobs.result_payload}
     * にだけ載り、利用者が「保存」を押したときに初めて{@link #save}が書く。
     */
    @Transactional(readOnly = true)
    public String generateBody(Long siteId, StaticContentType contentType) {
        Site site = siteRepository.findById(siteId)
                .orElseThrow(() -> new SiteNotFoundException("id " + siteId + " のサイトは登録されていません"));

        List<String> activePluginNames = fetchActivePluginNames(site);
        String prompt = buildPrompt(contentType, site, activePluginNames);
        String body = extractText(aiGenerationClient.generate(null, prompt, null));

        if (body.isBlank()) {
            throw new AiServiceGenerationException("LLMレスポンスが空でした。時間をおいて再度お試しください。");
        }
        return body;
    }

    /** サイトが存在することを確かめる。非同期ジョブの受理側が、ジョブを作る前に呼ぶ(issue #1409)。 */
    @Transactional(readOnly = true)
    public void requireSite(Long siteId) {
        if (!siteRepository.existsById(siteId)) {
            throw new SiteNotFoundException("id " + siteId + " のサイトは登録されていません");
        }
    }

    /**
     * 利用者が結果を確認して「保存」したときの書き込み(issue #1409)。同じサイト・種別があれば上書きする。
     * 下書きではなく、{@code static_content}への通常の保存である。
     */
    @Transactional
    public StaticContentResponse save(Long siteId, StaticContentType contentType, String body) {
        requireSite(siteId);
        return upsert(siteId, contentType, body);
    }

    private StaticContentResponse upsert(Long siteId, StaticContentType contentType, String body) {
        StaticContent entity = staticContentRepository.findBySiteIdAndContentType(siteId, contentType)
                .orElseGet(() -> {
                    StaticContent created = new StaticContent();
                    created.setSiteId(siteId);
                    created.setContentType(contentType);
                    return created;
                });
        entity.setBody(body);

        StaticContent saved = staticContentRepository.save(entity);
        return StaticContentResponse.from(saved);
    }

    /**
     * サイトの経路(managed=内部エージェント、非managedはSSH)に沿って有効化済みプラグイン名を取得する。
     */
    private List<String> fetchActivePluginNames(Site site) {
        if (site.isManagedWordpress()) {
            return agentPluginsClient.listActivePluginNames(site.getWpSlug());
        }
        SiteService.SiteDataSource dataSource = siteService.resolveDataSource(site);
        if (!dataSource.hasSsh()) {
            throw new AiServiceGenerationException(
                    "サイト '" + site.getSiteKey() + "' のプラグイン情報を取得できません(認証情報が未設定、"
                            + "またはSSHが利用できません)");
        }
        Map<String, String> creds = dataSource.sshCredentials();
        return bridgeClient.listActivePlugins(creds);
    }

    private String buildPrompt(StaticContentType contentType, Site site, List<String> activePluginNames) {
        String pluginList = activePluginNames.isEmpty()
                ? "(有効化されたプラグインはありません)"
                : activePluginNames.stream().collect(Collectors.joining("、"));

        return switch (contentType) {
            case PRIVACY_POLICY -> """
                    あなたは日本語のブログサイト向けプライバシーポリシーを作成する専門家です。
                    以下のサイト情報をもとに、コピー&ペーストしてそのまま公開ページに利用できる
                    プライバシーポリシーの本文を作成してください。

                    サイト名: %s
                    サイトURL: %s
                    有効化されているプラグイン: %s

                    要件:
                    1. 本文は```text ... ```で囲んで出力してください(前置きや説明文は一切含めないこと)
                    2. 有効化されているプラグインからアクセス解析(Google Analytics等)、広告配信(AdSense等)、
                       お問い合わせフォームでの個人情報収集などが推測される場合は、該当する項目を具体的に含めてください
                    3. 一般的な項目(取得する情報、利用目的、第三者提供、Cookie の使用、免責事項、お問い合わせ先)を含めてください
                    4. Markdown記法は使わず、見出しと本文のみのプレーンテキストで構成してください
                    """.formatted(site.getName(), site.getBaseUrl(), pluginList);
            case OPERATOR_INFO -> """
                    あなたは日本語のブログサイト向け運営者情報ページを作成する専門家です。
                    以下のサイト情報をもとに、コピー&ペーストしてそのまま公開ページに利用できる
                    運営者情報の本文を作成してください。

                    サイト名: %s
                    サイトURL: %s
                    有効化されているプラグイン: %s

                    要件:
                    1. 本文は```text ... ```で囲んで出力してください(前置きや説明文は一切含めないこと)
                    2. サイト名、運営形態、お問い合わせ方法、免責事項など、一般的な運営者情報ページに
                       含まれる項目を記載してください
                    3. 個人が特定される固有の氏名・住所・電話番号などは実在するかのように断定せず、
                       「[運営者名を入力]」のようなプレースホルダーで示してください
                    4. Markdown記法は使わず、見出しと本文のみのプレーンテキストで構成してください
                    """.formatted(site.getName(), site.getBaseUrl(), pluginList);
            case TERMS_OF_SERVICE -> """
                    あなたは日本語のブログサイト向け利用規約を作成する専門家です。
                    以下のサイト情報をもとに、コピー&ペーストしてそのまま公開ページに利用できる
                    利用規約の本文を作成してください。

                    サイト名: %s
                    サイトURL: %s
                    有効化されているプラグイン: %s

                    要件:
                    1. 本文は```text ... ```で囲んで出力してください(前置きや説明文は一切含めないこと)
                    2. 有効化されているプラグインからコメント機能、会員登録、お問い合わせフォームなどの
                       利用者とのやり取りが推測される場合は、該当する項目を具体的に含めてください
                    3. 一般的な項目(適用範囲、禁止事項、知的財産権、免責事項、規約の変更、準拠法、お問い合わせ先)を
                       条文形式(第1条、第2条…)で含めてください
                    4. Markdown記法は使わず、見出しと本文のみのプレーンテキストで構成してください
                    """.formatted(site.getName(), site.getBaseUrl(), pluginList);
        };
    }

    private String extractText(String response) {
        Matcher matcher = TEXT_PATTERN.matcher(response);
        if (matcher.find()) {
            return matcher.group(1).strip();
        }
        return response.strip();
    }
}
