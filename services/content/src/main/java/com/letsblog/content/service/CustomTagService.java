package com.letsblog.content.service;

import com.letsblog.content.aop.AuditLog;
import com.letsblog.content.client.ProjectBridgeClient;
import com.letsblog.content.domain.AuditLogAction;
import com.letsblog.content.domain.CustomTag;
import com.letsblog.content.domain.CustomTagFormat;
import com.letsblog.content.domain.EmbedTagType;
import com.letsblog.content.dto.CustomTagRequest;
import com.letsblog.content.dto.CustomTagResponse;
import com.letsblog.content.dto.TagDesignColors;
import com.letsblog.content.repository.CustomTagRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * legacy-apiのCustomTagServiceと同じ実装(issue #576でcontent-serviceへ移管)。
 * cssSelectorPrefixの解決先はProjectService(legacy-api)から本サービス自身が所有する
 * ProjectContentSettingsServiceへ、組み込みタグのデザイン色解決はTagDesignSettingService
 * (legacy-apiに残るドメイン、tag_design_settings)から{@link ProjectBridgeClient}経由の
 * 内部ブリッジへ切り替えた。
 */
@Service
public class CustomTagService {

    private static final Pattern OPAQUE_AT_RULE_PATTERN =
            Pattern.compile("(?i)^@(-\\w+-)?(keyframes|font-face|page)\\b");

    private final CustomTagRepository customTagRepository;
    private final AdminAuthorizationService adminAuthorizationService;
    private final ProjectBridgeClient projectBridgeClient;
    private final CurrentActorService currentActorService;
    private final TocStyleRenderService tocStyleRenderService;
    private final BlogCardTagRenderService blogCardTagRenderService;
    private final AmazonTagRenderService amazonTagRenderService;
    private final ProjectContentSettingsService projectContentSettingsService;

    public CustomTagService(CustomTagRepository customTagRepository,
                             AdminAuthorizationService adminAuthorizationService,
                             ProjectBridgeClient projectBridgeClient,
                             CurrentActorService currentActorService,
                             TocStyleRenderService tocStyleRenderService,
                             BlogCardTagRenderService blogCardTagRenderService,
                             AmazonTagRenderService amazonTagRenderService,
                             ProjectContentSettingsService projectContentSettingsService) {
        this.customTagRepository = customTagRepository;
        this.adminAuthorizationService = adminAuthorizationService;
        this.projectBridgeClient = projectBridgeClient;
        this.currentActorService = currentActorService;
        this.tocStyleRenderService = tocStyleRenderService;
        this.blogCardTagRenderService = blogCardTagRenderService;
        this.amazonTagRenderService = amazonTagRenderService;
        this.projectContentSettingsService = projectContentSettingsService;
    }

    @AuditLog(action = AuditLogAction.CUSTOM_TAG_CREATED, resourceType = "CUSTOM_TAG")
    @Transactional
    public CustomTagResponse create(CustomTagRequest request) {
        adminAuthorizationService.requireAdmin();

        Long projectId = request.projectId();
        findDuplicate(request.tagName(), projectId).ifPresent(existing -> {
            throw new IllegalArgumentException("タグ名 '" + request.tagName() + "' は既に登録されています");
        });

        CustomTag tag = new CustomTag();
        tag.setTagName(request.tagName());
        tag.setHtmlTemplate(request.htmlTemplate());
        tag.setDescription(request.description());
        tag.setCssContent(request.cssContent());
        tag.setTagFormat(request.tagFormat() != null ? request.tagFormat() : CustomTagFormat.BLOCK);
        tag.setProjectId(projectId);

        return CustomTagResponse.from(customTagRepository.save(tag));
    }

    @AuditLog(action = AuditLogAction.CUSTOM_TAG_UPDATED, resourceType = "CUSTOM_TAG")
    @Transactional
    public CustomTagResponse update(Long id, CustomTagRequest request) {
        adminAuthorizationService.requireAdmin();

        CustomTag tag = customTagRepository.findById(id)
                .orElseThrow(() -> new CustomTagNotFoundException("id " + id + " のカスタムタグは登録されていません"));

        // projectIdの変更は許可しない(リクエストに含まれていても無視する)
        Long projectId = tag.getProjectId();
        findDuplicate(request.tagName(), projectId)
                .filter(existing -> !existing.getId().equals(id))
                .ifPresent(existing -> {
                    throw new IllegalArgumentException("タグ名 '" + request.tagName() + "' は既に登録されています");
                });

        tag.setTagName(request.tagName());
        tag.setHtmlTemplate(request.htmlTemplate());
        tag.setDescription(request.description());
        tag.setCssContent(request.cssContent());
        tag.setTagFormat(request.tagFormat() != null ? request.tagFormat() : CustomTagFormat.BLOCK);

        return CustomTagResponse.from(customTagRepository.save(tag));
    }

    @AuditLog(action = AuditLogAction.CUSTOM_TAG_DELETED, resourceType = "CUSTOM_TAG")
    @Transactional
    public void delete(Long id) {
        adminAuthorizationService.requireAdmin();

        if (!customTagRepository.existsById(id)) {
            throw new CustomTagNotFoundException("id " + id + " のカスタムタグは登録されていません");
        }
        customTagRepository.deleteById(id);
    }

    /**
     * projectId が null ならグローバルタグのみ、指定時はそのプロジェクトのタグ + グローバルタグを返す。
     */
    @Transactional(readOnly = true)
    public List<CustomTagResponse> list(Long projectId) {
        List<CustomTag> tags = projectId == null
                ? customTagRepository.findByProjectIdIsNull()
                : customTagRepository.findByProjectIdOrProjectIdIsNull(projectId);
        return tags.stream().map(CustomTagResponse::from).toList();
    }

    /**
     * WordPressへ一括貼り付けするための統合CSS。list()と同じスコープ規約(projectId未指定=グローバルのみ、
     * 指定時はそのプロジェクト+グローバル)で、cssContentが空でないタグのみを連結する。
     * テーマCSSは含めない(WordPress側に既存のため重複・競合の原因になるため)。
     * projectIdが指定されている場合、そのプロジェクトの[toc]/[blogcard]/[amazon]組み込みタグの
     * デザインCSSも先頭に含める(組み込みタグのデザインはプロジェクト単位のためprojectId未指定時は対象外)。
     */
    @Transactional(readOnly = true)
    public String buildCssBundle(Long projectId) {
        List<CustomTag> tags = projectId == null
                ? customTagRepository.findByProjectIdIsNull()
                : customTagRepository.findByProjectIdOrProjectIdIsNull(projectId);
        String prefix = projectId == null ? null : resolveCssSelectorPrefix(projectId);
        String embedTagCss = projectId == null ? "" : applySelectorPrefix(buildEmbedTagCss(projectId), prefix);
        return embedTagCss + buildCssFrom(tags, prefix);
    }

    /**
     * プロジェクト詳細のカスタムタグ画面向け。グローバルタグを含めず、指定プロジェクトのタグのみを返す。
     */
    @Transactional(readOnly = true)
    public List<CustomTagResponse> listByProject(Long projectId) {
        return customTagRepository.findByProjectId(projectId).stream().map(CustomTagResponse::from).toList();
    }

    /**
     * プロジェクト詳細/プロジェクト一覧向けの統合CSS。buildCssBundle()と異なりグローバルタグは含めず、
     * 指定プロジェクトのタグのみを連結する。プロジェクトの[toc]/[blogcard]/[amazon]組み込みタグの
     * デザインCSSも先頭に含める。
     */
    @Transactional(readOnly = true)
    public String buildProjectCssBundle(Long projectId) {
        String prefix = resolveCssSelectorPrefix(projectId);
        return applySelectorPrefix(buildEmbedTagCss(projectId), prefix)
                + buildCssFrom(customTagRepository.findByProjectId(projectId), prefix);
    }

    /**
     * プロジェクトの[toc]/[blogcard]/[amazon]組み込みタグのデザインCSSを連結する。
     * 記事本文への注入(TocStyleRenderService等)と同じbuildStyle()を再利用し、
     * デザイン未保存のタグ種別もDesignPreset.DEFAULTのCSSとして含める。
     */
    private String buildEmbedTagCss(Long projectId) {
        StringBuilder sb = new StringBuilder();
        for (EmbedTagType tagType : EmbedTagType.values()) {
            TagDesignColors colors = projectBridgeClient.toColors(projectBridgeClient.resolveTagDesign(
                    projectId, tagType.name(), currentActorService.getAuthorizationHeader()));
            String style = switch (tagType) {
                case TOC -> tocStyleRenderService.buildStyle(colors);
                case BLOGCARD -> blogCardTagRenderService.buildStyle(colors);
                case AMAZON -> amazonTagRenderService.buildStyle(colors);
            };
            sb.append("/* === ").append(tagType.name().toLowerCase()).append(" (組み込みタグ) === */\n");
            sb.append(style).append("\n\n");
        }
        return sb.toString();
    }

    private String buildCssFrom(List<CustomTag> tags, String selectorPrefix) {
        StringBuilder sb = new StringBuilder();
        for (CustomTag tag : tags) {
            if (tag.getCssContent() == null || tag.getCssContent().isBlank()) {
                continue;
            }
            sb.append("/* === ").append(tag.getTagName()).append(" === */\n");
            sb.append(applySelectorPrefix(tag.getCssContent().strip(), selectorPrefix)).append("\n\n");
        }
        return sb.toString();
    }

    private String resolveCssSelectorPrefix(Long projectId) {
        return projectContentSettingsService.resolveCssSelectorPrefix(projectId);
    }

    /**
     * カスタムタグ管理画面のプレビュー用。DBに保存済みかどうかを問わず、統合CSSバンドルと
     * 同じプレフィックス付与ルールでCSSを変換する(issue #335)。
     */
    @Transactional(readOnly = true)
    public String previewCss(String css, Long projectId) {
        return applySelectorPrefix(css, resolveCssSelectorPrefix(projectId));
    }

    /**
     * CSSの各セレクタ宣言の先頭に `.prefix ` を付与し、WordPressテーマ側のCSSとのクラス名衝突を防ぐ(issue #298)。
     * 「1行に1つのセレクタ、`{`も同じ行」という単純な前提では、複数のルールが改行なしで連結されたCSS
     * (組み込みタグのデザインCSS等、issue #307)や、セレクタが複数行にまたがるCSSでプリフィックスが
     * 付与されない箇所が生じるため、コメント/文字列リテラル/波括弧の深さを追跡する1パスのスキャナで処理する。
     * `@media`/`@supports` 等はネストしたセレクタにも引き続きプリフィックスを付与するが、
     * `@keyframes`(ベンダープレフィックス含む)/`@font-face`/`@page` の中身はセレクタではないため対象外とする。
     */
    private String applySelectorPrefix(String css, String selectorPrefix) {
        if (css == null || css.isEmpty() || selectorPrefix == null || selectorPrefix.isBlank()) {
            return css == null ? "" : css;
        }

        StringBuilder output = new StringBuilder();
        StringBuilder buffer = new StringBuilder();
        // trueなら、次に現れる`{`の直前のbufferは「セレクタ」として扱いプリフィックスを付与する対象
        Deque<Boolean> selectorContextStack = new ArrayDeque<>();
        selectorContextStack.push(true);
        boolean inComment = false;
        boolean inString = false;
        char stringDelimiter = 0;

        int length = css.length();
        for (int i = 0; i < length; i++) {
            char c = css.charAt(i);

            if (inComment) {
                buffer.append(c);
                if (c == '*' && i + 1 < length && css.charAt(i + 1) == '/') {
                    buffer.append('/');
                    i++;
                    inComment = false;
                }
                continue;
            }
            if (inString) {
                buffer.append(c);
                if (c == '\\' && i + 1 < length) {
                    buffer.append(css.charAt(i + 1));
                    i++;
                    continue;
                }
                if (c == stringDelimiter) {
                    inString = false;
                }
                continue;
            }
            if (c == '/' && i + 1 < length && css.charAt(i + 1) == '*') {
                // bufferがここまで空白のみ(=直前に選択子/at-ruleの文字が無い)なら、コメントは
                // 独立したブロックコメントとみなし、セレクタ判定用のbufferを汚さないよう直接outputへ流す。
                if (buffer.toString().isBlank()) {
                    output.append(buffer);
                    buffer.setLength(0);
                    output.append(c);
                    i++;
                    output.append(css.charAt(i));
                    i++;
                    while (i < length) {
                        char commentChar = css.charAt(i);
                        output.append(commentChar);
                        if (commentChar == '*' && i + 1 < length && css.charAt(i + 1) == '/') {
                            output.append('/');
                            i++;
                            break;
                        }
                        i++;
                    }
                    continue;
                }
                inComment = true;
                buffer.append(c);
                continue;
            }
            if (c == '"' || c == '\'') {
                inString = true;
                stringDelimiter = c;
                buffer.append(c);
                continue;
            }

            if (c == '{') {
                String text = buffer.toString();
                boolean isSelectorContext = Boolean.TRUE.equals(selectorContextStack.peek());
                String trimmed = text.strip();

                if (!isSelectorContext || trimmed.isEmpty() || trimmed.startsWith("@")) {
                    output.append(text).append(c);
                    boolean opensOpaqueBlock = trimmed.startsWith("@") && OPAQUE_AT_RULE_PATTERN.matcher(trimmed).find();
                    selectorContextStack.push(isSelectorContext && !trimmed.startsWith("@") ? false
                            : isSelectorContext && !opensOpaqueBlock);
                } else {
                    output.append(prefixSelectorList(text, selectorPrefix)).append(' ').append(c);
                    selectorContextStack.push(false);
                }
                buffer.setLength(0);
                continue;
            }
            if (c == '}') {
                output.append(buffer).append(c);
                buffer.setLength(0);
                if (selectorContextStack.size() > 1) {
                    selectorContextStack.pop();
                }
                continue;
            }
            buffer.append(c);
        }
        output.append(buffer);
        return output.toString();
    }

    /**
     * カンマ区切りのセレクタリスト(複数行にまたがっていてもよい)の各セレクタに `.prefix ` を付与する。
     * `:not(a, b)` のような関数擬似クラス内のカンマでは分割しないよう括弧の深さを見ながら分割する。
     * 子孫結合子(スペース区切り)にするのは、実際にレンダリングされた要素自体にプリフィックスクラスを
     * 付与するのではなく、記事本文全体を囲むRenderedContentWrapperServiceの&lt;div&gt;にプリフィックス
     * クラスを持たせ、その祖先要素として一致させる設計のため。
     */
    private String prefixSelectorList(String selectorListText, String selectorPrefix) {
        List<String> selectors = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int parenDepth = 0;
        for (int i = 0; i < selectorListText.length(); i++) {
            char c = selectorListText.charAt(i);
            if (c == '(') {
                parenDepth++;
            } else if (c == ')') {
                parenDepth = Math.max(0, parenDepth - 1);
            }
            if (c == ',' && parenDepth == 0) {
                selectors.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        selectors.add(current.toString());

        return selectors.stream()
                .map(selector -> selector.strip().replaceAll("\\s+", " "))
                .filter(selector -> !selector.isEmpty())
                .map(selector -> "." + selectorPrefix + " " + selector)
                .collect(Collectors.joining(", "));
    }

    private Optional<CustomTag> findDuplicate(String tagName, Long projectId) {
        return projectId == null
                ? customTagRepository.findByTagNameAndProjectIdIsNull(tagName)
                : customTagRepository.findByTagNameAndProjectId(tagName, projectId);
    }
}
