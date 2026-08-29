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
 * この場合はDBを検索せず、常に固定のデフォルト値(DesignPreset.DEFAULTの色 / カスタムHTMLテンプレート無し)
 * を返す暫定対応とする。tag_design_settings.project_idはNOT NULL + projects(id)へのFKであり、
 * CustomTagRenderServiceのような「project_id IS NULLの行を運用者が保存できる設定可能なグローバル既定」は
 * 現状のスキーマでは持てない。したがってここで返る値は運用者が変更できないハードコード既定であり、
 * 設定可能なグローバル既定が必要になった場合はissue #763で対応する。
 */
@Service
public class TagDesignSettingService {

    private final TagDesignSettingRepository repository;

    public TagDesignSettingService(TagDesignSettingRepository repository) {
        this.repository = repository;
    }

    /**
     * レンダリング時(BlogCardTagRenderService等)に使う、確定済みの3色を返す。
     * projectIdがnull(プロジェクト未紐付けサイトへの公開、issue #760)の場合はDBを検索せず、
     * 常にDesignPreset.DEFAULTの色を返す。設定可能なグローバル既定行を引いているわけではなく、
     * 変更できないハードコード既定を返す暫定対応である(理由と将来対応はクラスJavadoc / issue #763参照)。
     */
    @Transactional(readOnly = true)
    public TagDesignColors resolveColors(Long projectId, EmbedTagType tagType) {
        if (projectId == null) {
            return presetColors(DesignPreset.DEFAULT);
        }
        return repository.findByProjectIdAndTagType(projectId, tagType)
                .map(s -> new TagDesignColors(s.getBackgroundColor(), s.getTextColor(), s.getAccentColor(), s.getCustomCss()))
                .orElseGet(() -> presetColors(DesignPreset.DEFAULT));
    }

    /**
     * レンダリング時に使うHTMLテンプレートを返す。未設定(保存なし、または保存済みだが空欄)の場合はnullを返し、
     * 呼び出し側は従来どおりのハードコードされたHTML構造にフォールバックする。
     * projectIdがnull(プロジェクト未紐付けサイト、issue #760)の場合はDBを検索せず、常に
     * 「カスタムテンプレート無し」= nullを返す。設定可能なグローバル既定行を引いているわけではなく、
     * 変更できないハードコード既定を返す暫定対応である(理由と将来対応はクラスJavadoc / issue #763参照)。
     */
    @Transactional(readOnly = true)
    public String resolveHtmlTemplate(Long projectId, EmbedTagType tagType) {
        if (projectId == null) {
            return null;
        }
        return repository.findByProjectIdAndTagType(projectId, tagType)
                .map(TagDesignSetting::getHtmlTemplate)
                .filter(template -> template != null && !template.isBlank())
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public TagDesignSettingsOverviewResponse getOverview(Long projectId) {
        List<TagDesignPresetResponse> presets = Arrays.stream(DesignPreset.values())
                .map(p -> new TagDesignPresetResponse(p.id(), p.label(), p.backgroundColor(), p.textColor(), p.accentColor()))
                .toList();

        Map<EmbedTagType, TagDesignSetting> saved = repository.findByProjectId(projectId).stream()
                .collect(Collectors.toMap(TagDesignSetting::getTagType, s -> s));

        List<TagDesignSettingResponse> settings = Arrays.stream(EmbedTagType.values())
                .map(type -> toResponse(type, saved.get(type)))
                .toList();

        return new TagDesignSettingsOverviewResponse(presets, settings);
    }

    @Transactional
    public TagDesignSettingResponse save(Long projectId, EmbedTagType tagType, SaveTagDesignSettingRequest request) {
        DesignPreset preset = DesignPreset.fromId(request.presetId());

        TagDesignSetting entity = repository.findByProjectIdAndTagType(projectId, tagType)
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
