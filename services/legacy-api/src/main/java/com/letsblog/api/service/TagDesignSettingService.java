package com.letsblog.api.service;

import com.letsblog.api.domain.DesignPreset;
import com.letsblog.api.domain.EmbedTagType;
import com.letsblog.api.domain.TagDesignSetting;
import com.letsblog.api.dto.SaveTagDesignSettingRequest;
import com.letsblog.api.dto.TagDesignColors;
import com.letsblog.api.dto.TagDesignPresetResponse;
import com.letsblog.api.dto.TagDesignSettingResponse;
import com.letsblog.api.dto.TagDesignSettingsOverviewResponse;
import com.letsblog.api.repository.TagDesignSettingRepository;
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
 */
@Service
public class TagDesignSettingService {

    private final TagDesignSettingRepository repository;

    public TagDesignSettingService(TagDesignSettingRepository repository) {
        this.repository = repository;
    }

    /** レンダリング時(BlogCardTagRenderService等)に使う、確定済みの3色を返す。 */
    @Transactional(readOnly = true)
    public TagDesignColors resolveColors(Long projectId, EmbedTagType tagType) {
        return repository.findByProjectIdAndTagType(projectId, tagType)
                .map(s -> new TagDesignColors(s.getBackgroundColor(), s.getTextColor(), s.getAccentColor(), s.getCustomCss()))
                .orElseGet(() -> presetColors(DesignPreset.DEFAULT));
    }

    /**
     * レンダリング時に使うHTMLテンプレートを返す。未設定(保存なし、または保存済みだが空欄)の場合はnullを返し、
     * 呼び出し側は従来どおりのハードコードされたHTML構造にフォールバックする。
     */
    @Transactional(readOnly = true)
    public String resolveHtmlTemplate(Long projectId, EmbedTagType tagType) {
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
