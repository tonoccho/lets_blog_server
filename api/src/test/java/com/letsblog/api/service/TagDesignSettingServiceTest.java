package com.letsblog.api.service;

import com.letsblog.api.domain.DesignPreset;
import com.letsblog.api.domain.EmbedTagType;
import com.letsblog.api.domain.TagDesignSetting;
import com.letsblog.api.dto.SaveTagDesignSettingRequest;
import com.letsblog.api.dto.TagDesignColors;
import com.letsblog.api.dto.TagDesignSettingResponse;
import com.letsblog.api.dto.TagDesignSettingsOverviewResponse;
import com.letsblog.api.repository.TagDesignSettingRepository;
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
                new SaveTagDesignSettingRequest("dark", "#111111", "#eeeeee", "#60a5fa", null);
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
                new SaveTagDesignSettingRequest("vivid", "#fff7ed", "#7c2d12", "#ea580c", null);
        service.save(PROJECT_ID, EmbedTagType.BLOGCARD, request);

        assertEquals("vivid", existing.getPresetId());
        assertEquals("#fff7ed", existing.getBackgroundColor());
    }

    @Test
    void save_customCssを渡すと保存され応答にも含まれる() {
        when(repository.findByProjectIdAndTagType(PROJECT_ID, EmbedTagType.TOC)).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SaveTagDesignSettingRequest request =
                new SaveTagDesignSettingRequest("dark", "#111111", "#eeeeee", "#60a5fa", ".lb-toc-list{font-weight:bold;}");
        TagDesignSettingResponse response = service.save(PROJECT_ID, EmbedTagType.TOC, request);

        assertEquals(".lb-toc-list{font-weight:bold;}", response.customCss());
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

    @Test
    void save_不明なプリセットIDはIllegalArgumentExceptionを投げる() {
        SaveTagDesignSettingRequest request =
                new SaveTagDesignSettingRequest("unknown-preset", "#111111", "#eeeeee", "#60a5fa", null);

        assertThrows(IllegalArgumentException.class, () -> service.save(PROJECT_ID, EmbedTagType.TOC, request));
    }
}
