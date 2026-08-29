package com.letsblog.project.controller;

import com.letsblog.project.domain.DesignPreset;
import com.letsblog.project.domain.EmbedTagType;
import com.letsblog.project.dto.TagDesignBridgeResponse;
import com.letsblog.project.dto.TagDesignColors;
import com.letsblog.project.service.TagDesignSettingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
 */
@ExtendWith(MockitoExtension.class)
class TagDesignInternalControllerTest {

    @Mock
    private TagDesignSettingService tagDesignSettingService;

    @Test
    void tagDesign_色とHTMLテンプレートをまとめて返す() {
        when(tagDesignSettingService.resolveColors(1L, EmbedTagType.TOC))
                .thenReturn(new TagDesignColors("#ffffff", "#000000", "#ff0000", "custom { color: red; }"));
        when(tagDesignSettingService.resolveHtmlTemplate(1L, EmbedTagType.TOC)).thenReturn("<div>{{content}}</div>");

        TagDesignBridgeResponse response = new TagDesignInternalController(tagDesignSettingService)
                .tagDesign(EmbedTagType.TOC, 1L);

        assertEquals("#ffffff", response.backgroundColor());
        assertEquals("#000000", response.textColor());
        assertEquals("#ff0000", response.accentColor());
        assertEquals("custom { color: red; }", response.customCss());
        assertEquals("<div>{{content}}</div>", response.htmlTemplate());
    }

    @Test
    void tagDesign_未設定なら_htmlTemplateはnull() {
        when(tagDesignSettingService.resolveColors(2L, EmbedTagType.BLOGCARD))
                .thenReturn(new TagDesignColors("#eeeeee", "#111111", "#0000ff", null));
        when(tagDesignSettingService.resolveHtmlTemplate(2L, EmbedTagType.BLOGCARD)).thenReturn(null);

        TagDesignBridgeResponse response = new TagDesignInternalController(tagDesignSettingService)
                .tagDesign(EmbedTagType.BLOGCARD, 2L);

        assertEquals(null, response.htmlTemplate());
        assertEquals(null, response.customCss());
    }

    /**
     * issue #760: content-service→legacy-api経由でprojectId=nullのまま届くと、URIテンプレート展開の
     * 結果クエリは{@code ?projectId=}(空文字)になる。required=trueだと400になっていた経路。
     */
    @Test
    void tagDesign_projectIdが空文字でも200でグローバル既定を返す() throws Exception {
        when(tagDesignSettingService.resolveColors(null, EmbedTagType.TOC)).thenReturn(new TagDesignColors(
                DesignPreset.DEFAULT.backgroundColor(), DesignPreset.DEFAULT.textColor(),
                DesignPreset.DEFAULT.accentColor(), null));
        when(tagDesignSettingService.resolveHtmlTemplate(null, EmbedTagType.TOC)).thenReturn(null);

        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new TagDesignInternalController(tagDesignSettingService)).build();

        mockMvc.perform(get("/api/internal/project/tag-design/{tagType}", "TOC").param("projectId", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.backgroundColor").value(DesignPreset.DEFAULT.backgroundColor()))
                .andExpect(jsonPath("$.htmlTemplate").doesNotExist());
    }

    /** projectId指定ありの経路に回帰がないこと(HTTPレベル)。 */
    @Test
    void tagDesign_projectId指定ありは従来どおりそのプロジェクトの設定を返す() throws Exception {
        when(tagDesignSettingService.resolveColors(7L, EmbedTagType.AMAZON))
                .thenReturn(new TagDesignColors("#111111", "#eeeeee", "#ff0000", null));
        when(tagDesignSettingService.resolveHtmlTemplate(7L, EmbedTagType.AMAZON)).thenReturn("<div>{{price}}</div>");

        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new TagDesignInternalController(tagDesignSettingService)).build();

        mockMvc.perform(get("/api/internal/project/tag-design/{tagType}", "AMAZON").param("projectId", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.backgroundColor").value("#111111"))
                .andExpect(jsonPath("$.htmlTemplate").value("<div>{{price}}</div>"));
    }
}
