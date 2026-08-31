package com.letsblog.project.service;

import com.letsblog.project.domain.DesignPreset;
import com.letsblog.project.domain.EmbedTagType;
import com.letsblog.project.domain.TagDesignSetting;
import com.letsblog.project.dto.SaveTagDesignSettingRequest;
import com.letsblog.project.dto.TagDesignColors;
import com.letsblog.project.dto.TagDesignPresetResponse;
import com.letsblog.project.dto.TagDesignSettingResponse;
import com.letsblog.project.dto.TagDesignSettingsOverviewResponse;
import com.letsblog.project.repository.TagDesignSettingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * [toc]/[blogcard]/[amazon] 組み込みタグのデザイン(プリセット+背景色/テキスト色/アクセントカラー)を
 * プロジェクト単位で管理する。保存されていないプロジェクト/タグ種別はDesignPreset.DEFAULTの色を返す
 * (「デフォルト状態でも使える標準設定」の要件を、保存なしのフォールバックとして満たす)。
 *
 * <p>プロジェクトに紐付いていないサイトへの公開ではprojectIdがnullで解決要求が来る(issue #760)。
 * #763で{@code tag_design_settings.project_id}をnullable化したため、この場合は
 * {@code project_id IS NULL}の<b>グローバル既定行</b>を引く。content-serviceの
 * {@code custom_tags}が{@code findByProjectIdIsNull()}で行っているのと同じ方式で、
 * 運用者が管理画面から設定できる。グローバル行も保存されていなければ、従来どおり
 * DesignPreset.DEFAULTの色 / カスタムHTMLテンプレート無しへフォールバックする。
 *
 * <p><b>プロジェクト行からグローバル行へのフォールバックは行わない。</b>
 * プロジェクトに紐付いた解決で未設定だった場合は、グローバル行ではなくDesignPreset.DEFAULTを返す
 * (#763以前と同じ)。グローバル行を挟むと既存プロジェクトの見た目が
 * グローバル設定の変更で勝手に変わってしまい、回帰になるため。
 * グローバル行はあくまで「projectIdがnullのとき」だけの解決先である。
 */
@Service
public class TagDesignSettingService {

    private final TagDesignSettingRepository repository;

    public TagDesignSettingService(TagDesignSettingRepository repository) {
        this.repository = repository;
    }

    /**
     * レンダリング時(BlogCardTagRenderService等)に使う、確定済みの3色を返す。
     * projectIdがnull(プロジェクト未紐付けサイトへの公開、issue #760)の場合はグローバル既定行を引く(#763)。
     * 該当行が無ければDesignPreset.DEFAULTの色を返す。
     */
    @Transactional(readOnly = true)
    public TagDesignColors resolveColors(Long projectId, EmbedTagType tagType) {
        return findSetting(projectId, tagType)
                .map(s -> new TagDesignColors(s.getBackgroundColor(), s.getTextColor(), s.getAccentColor(), s.getCustomCss()))
                .orElseGet(() -> presetColors(DesignPreset.DEFAULT));
    }

    /**
     * レンダリング時に使うHTMLテンプレートを返す。未設定(保存なし、または保存済みだが空欄)の場合はnullを返し、
     * 呼び出し側は従来どおりのハードコードされたHTML構造にフォールバックする。
     * projectIdがnull(プロジェクト未紐付けサイト、issue #760)の場合はグローバル既定行を引く(#763)。
     * 該当行が無い、または保存済みだがテンプレートが空欄なら、従来どおりnullを返す。
     */
    @Transactional(readOnly = true)
    public String resolveHtmlTemplate(Long projectId, EmbedTagType tagType) {
        return findSetting(projectId, tagType)
                .map(TagDesignSetting::getHtmlTemplate)
                .filter(template -> template != null && !template.isBlank())
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public TagDesignSettingsOverviewResponse getOverview(Long projectId) {
        List<TagDesignPresetResponse> presets = Arrays.stream(DesignPreset.values())
                .map(p -> new TagDesignPresetResponse(p.id(), p.label(), p.backgroundColor(), p.textColor(), p.accentColor()))
                .toList();

        Map<EmbedTagType, TagDesignSetting> saved = findAllSettings(projectId).stream()
                .collect(Collectors.toMap(TagDesignSetting::getTagType, s -> s));

        List<TagDesignSettingResponse> settings = Arrays.stream(EmbedTagType.values())
                .map(type -> toResponse(type, saved.get(type)))
                .toList();

        return new TagDesignSettingsOverviewResponse(presets, settings);
    }

    @Transactional
    public TagDesignSettingResponse save(Long projectId, EmbedTagType tagType, SaveTagDesignSettingRequest request) {
        DesignPreset preset = DesignPreset.fromId(request.presetId());

        TagDesignSetting entity = findSetting(projectId, tagType)
                .orElseGet(TagDesignSetting::new);
        entity.setProjectId(projectId);
        entity.setTagType(tagType);
        entity.setPresetId(preset.id());
        entity.setBackgroundColor(request.backgroundColor());
        entity.setTextColor(request.textColor());
        entity.setAccentColor(request.accentColor());
        entity.setCustomCss(request.customCss());
        entity.setHtmlTemplate(request.htmlTemplate());

        TagDesignSetting saved = repository.save(entity);
        return toResponse(tagType, saved);
    }

    /**
     * projectIdのスコープで1件引く。
     *
     * <p>Spring Data JPAの{@code findByProjectIdAndTagType(null, ...)}は
     * {@code project_id = NULL}というSQLになり、NULL同士の比較は常にUNKNOWNなので何にも一致しない。
     * グローバル行を引くには{@code IS NULL}を使う専用のメソッドが要る(#763)。
     */
    private java.util.Optional<TagDesignSetting> findSetting(Long projectId, EmbedTagType tagType) {
        return projectId == null
                ? repository.findByProjectIdIsNullAndTagType(tagType)
                : repository.findByProjectIdAndTagType(projectId, tagType);
    }

    /** projectIdのスコープの全件。null なら グローバル行(project_id IS NULL)を返す(#763)。 */
    private List<TagDesignSetting> findAllSettings(Long projectId) {
        return projectId == null
                ? repository.findByProjectIdIsNull()
                : repository.findByProjectId(projectId);
    }

    private TagDesignColors presetColors(DesignPreset preset) {
        return new TagDesignColors(preset.backgroundColor(), preset.textColor(), preset.accentColor(), null);
    }

    private TagDesignSettingResponse toResponse(EmbedTagType tagType, TagDesignSetting saved) {
        if (saved != null) {
            return new TagDesignSettingResponse(
                    tagType, saved.getPresetId(), saved.getBackgroundColor(), saved.getTextColor(),
                    saved.getAccentColor(), saved.getCustomCss(), saved.getHtmlTemplate());
        }
        DesignPreset defaultPreset = DesignPreset.DEFAULT;
        return new TagDesignSettingResponse(
                tagType, defaultPreset.id(), defaultPreset.backgroundColor(), defaultPreset.textColor(),
                defaultPreset.accentColor(), null, null);
    }
}
