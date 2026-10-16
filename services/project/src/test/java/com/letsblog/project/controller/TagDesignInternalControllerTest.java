package com.letsblog.project.controller;

import com.letsblog.project.domain.DesignPreset;
import com.letsblog.project.domain.EmbedTagType;
import com.letsblog.project.domain.TagDesignSetting;
import com.letsblog.project.dto.TagDesignBridgeResponse;
import com.letsblog.project.repository.TagDesignSettingRepository;
import com.letsblog.project.service.TagDesignSettingService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TagDesignInternalControllerの回帰テスト(issue #577 stage3)。legacy-apiのContentBridgeController
 * #tagDesign()が、色+HTMLテンプレートをこのエンドポイント経由で1回で取得できることを検証する。
 * projectId未指定(プロジェクト未紐付けサイト、issue #760)でも400にならないことも検証する。
 *
 * <p>issue #1818: TagDesignSettingService は実物で、差し替えるのはDBのリポジトリだけ。
 */
class TagDesignInternalControllerTest {

    private final TagDesignSettingRepository repository = Mockito.mock(TagDesignSettingRepository.class);
    private final TagDesignSettingService service = new TagDesignSettingService(repository);

    private TagDesignSetting setting(Long projectId, EmbedTagType type, String bg, String text, String accent,
                                     String css, String html) {
        TagDesignSetting setting = new TagDesignSetting();
        setting.setProjectId(projectId);
        setting.setTagType(type);
        setting.setPresetId("dark");
        setting.setBackgroundColor(bg);
        setting.setTextColor(text);
        setting.setAccentColor(accent);
        setting.setCustomCss(css);
        setting.setHtmlTemplate(html);
        return setting;
    }

    @Test
    void tagDesign_色とHTMLテンプレートをまとめて返す() {
        when(repository.findByProjectIdAndTagType(1L, EmbedTagType.TOC)).thenReturn(Optional.of(setting(
                1L, EmbedTagType.TOC, "#ffffff", "#000000", "#ff0000", "custom { color: red; }", "<div>{{content}}</div>")));

        TagDesignBridgeResponse response = new TagDesignInternalController(service).tagDesign(EmbedTagType.TOC, 1L);

        assertEquals("#ffffff", response.backgroundColor());
        assertEquals("#000000", response.textColor());
        assertEquals("#ff0000", response.accentColor());
        assertEquals("custom { color: red; }", response.customCss());
        assertEquals("<div>{{content}}</div>", response.htmlTemplate());
    }

    @Test
    void tagDesign_テンプレート未設定なら_htmlTemplateはnull() {
        when(repository.findByProjectIdAndTagType(2L, EmbedTagType.BLOGCARD)).thenReturn(Optional.of(setting(
                2L, EmbedTagType.BLOGCARD, "#eeeeee", "#111111", "#0000ff", null, null)));

        TagDesignBridgeResponse response = new TagDesignInternalController(service).tagDesign(EmbedTagType.BLOGCARD, 2L);

        assertEquals("#eeeeee", response.backgroundColor());
        assertEquals(null, response.htmlTemplate());
        assertEquals(null, response.customCss());
    }

    @Test
    void tagDesign_保存が無ければ固定のデフォルト色でテンプレートはnull() {
        when(repository.findByProjectIdAndTagType(3L, EmbedTagType.AMAZON)).thenReturn(Optional.empty());

        TagDesignBridgeResponse response = new TagDesignInternalController(service).tagDesign(EmbedTagType.AMAZON, 3L);

        assertEquals(DesignPreset.DEFAULT.backgroundColor(), response.backgroundColor());
        assertEquals(null, response.htmlTemplate());
    }

    /**
     * issue #760: content-service→legacy-api経由でprojectId=nullのまま届くと、URIテンプレート展開の
     * 結果クエリは{@code ?projectId=}(空文字)になる。required=trueだと400になっていた経路。
     */
    @Test
    void tagDesign_projectIdが空文字でも200で固定のデフォルト値を返す() throws Exception {
        when(repository.findByProjectIdIsNullAndTagType(EmbedTagType.TOC)).thenReturn(Optional.empty());

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new TagDesignInternalController(service)).build();

        mockMvc.perform(get("/api/internal/project/tag-design/{tagType}", "TOC").param("projectId", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.backgroundColor").value(DesignPreset.DEFAULT.backgroundColor()))
                .andExpect(jsonPath("$.htmlTemplate").doesNotExist());
    }

    /** グローバル既定行があれば、projectId未指定ではその行を返す(issue #763)。 */
    @Test
    void tagDesign_projectIdが空ならグローバル既定行を返す() throws Exception {
        when(repository.findByProjectIdIsNullAndTagType(EmbedTagType.TOC)).thenReturn(Optional.of(setting(
                null, EmbedTagType.TOC, "#abcdef", "#123456", "#654321", null, "<p>{{toc}}</p>")));

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new TagDesignInternalController(service)).build();

        mockMvc.perform(get("/api/internal/project/tag-design/{tagType}", "TOC").param("projectId", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.backgroundColor").value("#abcdef"))
                .andExpect(jsonPath("$.htmlTemplate").value("<p>{{toc}}</p>"));
    }

    /** projectId指定ありの経路に回帰がないこと(HTTPレベル)。 */
    @Test
    void tagDesign_projectId指定ありは従来どおりそのプロジェクトの設定を返す() throws Exception {
        when(repository.findByProjectIdAndTagType(7L, EmbedTagType.AMAZON)).thenReturn(Optional.of(setting(
                7L, EmbedTagType.AMAZON, "#111111", "#eeeeee", "#ff0000", null, "<div>{{price}}</div>")));

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new TagDesignInternalController(service)).build();

        mockMvc.perform(get("/api/internal/project/tag-design/{tagType}", "AMAZON").param("projectId", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.backgroundColor").value("#111111"))
                .andExpect(jsonPath("$.htmlTemplate").value("<div>{{price}}</div>"));
    }
}
