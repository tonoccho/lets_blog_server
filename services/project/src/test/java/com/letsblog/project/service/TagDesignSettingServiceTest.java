package com.letsblog.project.service;

import com.letsblog.project.domain.DesignPreset;
import com.letsblog.project.domain.EmbedTagType;
import com.letsblog.project.domain.TagDesignSetting;
import com.letsblog.project.dto.SaveTagDesignSettingRequest;
import com.letsblog.project.dto.TagDesignColors;
import com.letsblog.project.dto.TagDesignSettingResponse;
import com.letsblog.project.dto.TagDesignSettingsOverviewResponse;
import com.letsblog.project.repository.TagDesignSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TagDesignSettingServiceTest {

    private static final Long PROJECT_ID = 1L;

    @Mock
    private TagDesignSettingRepository repository;

    private TagDesignSettingService service;

    @BeforeEach
    void setUp() {
        service = new TagDesignSettingService(repository);
    }

    private TagDesignSetting saved(EmbedTagType type, String presetId, String bg, String text, String accent) {
        TagDesignSetting setting = new TagDesignSetting();
        setting.setProjectId(PROJECT_ID);
        setting.setTagType(type);
        setting.setPresetId(presetId);
        setting.setBackgroundColor(bg);
        setting.setTextColor(text);
        setting.setAccentColor(accent);
        return setting;
    }

    @Test
    void resolveColors_未保存の場合はデフォルトプリセットの色を返す() {
        when(repository.findByProjectIdAndTagType(PROJECT_ID, EmbedTagType.TOC)).thenReturn(Optional.empty());

        TagDesignColors colors = service.resolveColors(PROJECT_ID, EmbedTagType.TOC);

        assertEquals(DesignPreset.DEFAULT.backgroundColor(), colors.backgroundColor());
        assertEquals(DesignPreset.DEFAULT.textColor(), colors.textColor());
        assertEquals(DesignPreset.DEFAULT.accentColor(), colors.accentColor());
    }

    @Test
    void resolveColors_保存済みの場合はその色を返す() {
        when(repository.findByProjectIdAndTagType(PROJECT_ID, EmbedTagType.BLOGCARD))
                .thenReturn(Optional.of(saved(EmbedTagType.BLOGCARD, "dark", "#111111", "#eeeeee", "#ff0000")));

        TagDesignColors colors = service.resolveColors(PROJECT_ID, EmbedTagType.BLOGCARD);

        assertEquals("#111111", colors.backgroundColor());
        assertEquals("#eeeeee", colors.textColor());
        assertEquals("#ff0000", colors.accentColor());
    }

    @Test
    void getOverview_全プリセットと3タグ種別分の設定を返す_未保存分はデフォルト() {
        when(repository.findByProjectId(PROJECT_ID)).thenReturn(
                List.of(saved(EmbedTagType.AMAZON, "vivid", "#fff7ed", "#7c2d12", "#ea580c")));

        TagDesignSettingsOverviewResponse overview = service.getOverview(PROJECT_ID);

        assertEquals(DesignPreset.values().length, overview.presets().size());
        assertEquals(EmbedTagType.values().length, overview.settings().size());

        TagDesignSettingResponse amazonSetting = overview.settings().stream()
                .filter(s -> s.tagType() == EmbedTagType.AMAZON).findFirst().orElseThrow();
        assertEquals("vivid", amazonSetting.presetId());
        assertEquals("#ea580c", amazonSetting.accentColor());

        TagDesignSettingResponse tocSetting = overview.settings().stream()
                .filter(s -> s.tagType() == EmbedTagType.TOC).findFirst().orElseThrow();
        assertEquals(DesignPreset.DEFAULT.id(), tocSetting.presetId());
        assertEquals(DesignPreset.DEFAULT.backgroundColor(), tocSetting.backgroundColor());
    }

    @Test
    void save_新規保存でリポジトリに保存される() {
        when(repository.findByProjectIdAndTagType(PROJECT_ID, EmbedTagType.TOC)).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SaveTagDesignSettingRequest request =
                new SaveTagDesignSettingRequest("dark", "#111111", "#eeeeee", "#60a5fa", null, null);
        TagDesignSettingResponse response = service.save(PROJECT_ID, EmbedTagType.TOC, request);

        assertEquals(EmbedTagType.TOC, response.tagType());
        assertEquals("dark", response.presetId());
        assertEquals("#111111", response.backgroundColor());

        ArgumentCaptor<TagDesignSetting> captor = ArgumentCaptor.forClass(TagDesignSetting.class);
        org.mockito.Mockito.verify(repository).save(captor.capture());
        assertEquals(PROJECT_ID, captor.getValue().getProjectId());
        assertEquals(EmbedTagType.TOC, captor.getValue().getTagType());
    }

    @Test
    void save_既存の設定があれば更新する() {
        TagDesignSetting existing = saved(EmbedTagType.BLOGCARD, "light", "#ffffff", "#1a1a1a", "#2563eb");
        when(repository.findByProjectIdAndTagType(PROJECT_ID, EmbedTagType.BLOGCARD))
                .thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SaveTagDesignSettingRequest request =
                new SaveTagDesignSettingRequest("vivid", "#fff7ed", "#7c2d12", "#ea580c", null, null);
        service.save(PROJECT_ID, EmbedTagType.BLOGCARD, request);

        assertEquals("vivid", existing.getPresetId());
        assertEquals("#fff7ed", existing.getBackgroundColor());
    }

    @Test
    void save_customCssを渡すと保存され応答にも含まれる() {
        when(repository.findByProjectIdAndTagType(PROJECT_ID, EmbedTagType.TOC)).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SaveTagDesignSettingRequest request =
                new SaveTagDesignSettingRequest("dark", "#111111", "#eeeeee", "#60a5fa", ".lb-toc-list{font-weight:bold;}", null);
        TagDesignSettingResponse response = service.save(PROJECT_ID, EmbedTagType.TOC, request);

        assertEquals(".lb-toc-list{font-weight:bold;}", response.customCss());
    }

    @Test
    void save_htmlTemplateを渡すと保存され応答にも含まれる() {
        when(repository.findByProjectIdAndTagType(PROJECT_ID, EmbedTagType.TOC)).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SaveTagDesignSettingRequest request =
                new SaveTagDesignSettingRequest("dark", "#111111", "#eeeeee", "#60a5fa", null, "<div>{{toc}}</div>");
        TagDesignSettingResponse response = service.save(PROJECT_ID, EmbedTagType.TOC, request);

        assertEquals("<div>{{toc}}</div>", response.htmlTemplate());
    }

    @Test
    void resolveHtmlTemplate_未保存の場合はnull() {
        when(repository.findByProjectIdAndTagType(PROJECT_ID, EmbedTagType.TOC)).thenReturn(Optional.empty());

        assertEquals(null, service.resolveHtmlTemplate(PROJECT_ID, EmbedTagType.TOC));
    }

    @Test
    void resolveHtmlTemplate_保存済みだが空欄の場合はnull() {
        TagDesignSetting setting = saved(EmbedTagType.TOC, "dark", "#111111", "#eeeeee", "#60a5fa");
        setting.setHtmlTemplate("   ");
        when(repository.findByProjectIdAndTagType(PROJECT_ID, EmbedTagType.TOC)).thenReturn(Optional.of(setting));

        assertEquals(null, service.resolveHtmlTemplate(PROJECT_ID, EmbedTagType.TOC));
    }

    @Test
    void resolveHtmlTemplate_保存済みの場合はその内容を返す() {
        TagDesignSetting setting = saved(EmbedTagType.BLOGCARD, "dark", "#111111", "#eeeeee", "#60a5fa");
        setting.setHtmlTemplate("<a href=\"{{url}}\">{{title}}</a>");
        when(repository.findByProjectIdAndTagType(PROJECT_ID, EmbedTagType.BLOGCARD)).thenReturn(Optional.of(setting));

        assertEquals("<a href=\"{{url}}\">{{title}}</a>", service.resolveHtmlTemplate(PROJECT_ID, EmbedTagType.BLOGCARD));
    }

    @Test
    void resolveColors_customCssが保存されていれば含めて返す() {
        TagDesignSetting setting = saved(EmbedTagType.TOC, "dark", "#111111", "#eeeeee", "#60a5fa");
        setting.setCustomCss(".lb-toc-list{font-weight:bold;}");
        when(repository.findByProjectIdAndTagType(PROJECT_ID, EmbedTagType.TOC)).thenReturn(Optional.of(setting));

        TagDesignColors colors = service.resolveColors(PROJECT_ID, EmbedTagType.TOC);

        assertEquals(".lb-toc-list{font-weight:bold;}", colors.customCss());
    }

    @Test
    void resolveColors_未保存の場合はcustomCssがnull() {
        when(repository.findByProjectIdAndTagType(PROJECT_ID, EmbedTagType.TOC)).thenReturn(Optional.empty());

        TagDesignColors colors = service.resolveColors(PROJECT_ID, EmbedTagType.TOC);

        assertEquals(null, colors.customCss());
    }

    /**
     * #763以前は、projectIdがnullのときDBを引かずハードコード既定を返していた
     * (project_idがNOT NULLでグローバル行を持てなかったため)。
     * V2でnullable化したので、いまはグローバル既定行を引く。
     */
    @Test
    void resolveColors_projectIdがnullならグローバル既定行を引く() {
        TagDesignSetting global = new TagDesignSetting();
        global.setProjectId(null);
        global.setTagType(EmbedTagType.TOC);
        global.setPresetId("ocean");
        global.setBackgroundColor("#001122");
        global.setTextColor("#ffffff");
        global.setAccentColor("#3399ff");
        global.setCustomCss(".toc{}");
        when(repository.findByProjectIdIsNullAndTagType(EmbedTagType.TOC)).thenReturn(Optional.of(global));

        TagDesignColors colors = service.resolveColors(null, EmbedTagType.TOC);

        assertEquals("#001122", colors.backgroundColor());
        assertEquals("#ffffff", colors.textColor());
        assertEquals("#3399ff", colors.accentColor());
        assertEquals(".toc{}", colors.customCss());
    }

    @Test
    void resolveColors_projectIdがnullでグローバル行も無ければデフォルト色を返す() {
        when(repository.findByProjectIdIsNullAndTagType(EmbedTagType.TOC)).thenReturn(Optional.empty());

        TagDesignColors colors = service.resolveColors(null, EmbedTagType.TOC);

        assertEquals(DesignPreset.DEFAULT.backgroundColor(), colors.backgroundColor());
        assertEquals(DesignPreset.DEFAULT.textColor(), colors.textColor());
        assertEquals(DesignPreset.DEFAULT.accentColor(), colors.accentColor());
        assertEquals(null, colors.customCss());
    }

    /**
     * projectIdがnullの解決だけがグローバル行を見る。プロジェクトに紐付いた解決が
     * 未設定だった場合はグローバル行へフォールバックせず、従来どおりDEFAULTを返す
     * (グローバル設定の変更で既存プロジェクトの見た目が変わるのを避けるため。#763)。
     */
    @Test
    void resolveColors_プロジェクト未設定時はグローバル行へフォールバックしない() {
        when(repository.findByProjectIdAndTagType(7L, EmbedTagType.TOC)).thenReturn(Optional.empty());

        TagDesignColors colors = service.resolveColors(7L, EmbedTagType.TOC);

        assertEquals(DesignPreset.DEFAULT.backgroundColor(), colors.backgroundColor());
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never())
                .findByProjectIdIsNullAndTagType(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void resolveHtmlTemplate_projectIdがnullならグローバル既定行のテンプレートを返す() {
        TagDesignSetting global = new TagDesignSetting();
        global.setProjectId(null);
        global.setTagType(EmbedTagType.BLOGCARD);
        global.setHtmlTemplate("<div>global</div>");
        when(repository.findByProjectIdIsNullAndTagType(EmbedTagType.BLOGCARD))
                .thenReturn(Optional.of(global));

        assertEquals("<div>global</div>", service.resolveHtmlTemplate(null, EmbedTagType.BLOGCARD));
    }

    @Test
    void resolveHtmlTemplate_projectIdがnullでグローバル行が無ければnullを返す() {
        when(repository.findByProjectIdIsNullAndTagType(EmbedTagType.BLOGCARD))
                .thenReturn(Optional.empty());

        assertEquals(null, service.resolveHtmlTemplate(null, EmbedTagType.BLOGCARD));
    }

    @Test
    void save_不明なプリセットIDはIllegalArgumentExceptionを投げる() {
        SaveTagDesignSettingRequest request =
                new SaveTagDesignSettingRequest("unknown-preset", "#111111", "#eeeeee", "#60a5fa", null, null);

        assertThrows(IllegalArgumentException.class, () -> service.save(PROJECT_ID, EmbedTagType.TOC, request));
    }
}
