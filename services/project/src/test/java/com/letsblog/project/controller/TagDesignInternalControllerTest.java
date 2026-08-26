package com.letsblog.project.controller;

import com.letsblog.project.domain.EmbedTagType;
import com.letsblog.project.dto.TagDesignBridgeResponse;
import com.letsblog.project.dto.TagDesignColors;
import com.letsblog.project.service.TagDesignSettingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/**
 * TagDesignInternalControllerの回帰テスト(issue #577 stage3)。legacy-apiのContentBridgeController
 * #tagDesign()が、色+HTMLテンプレートをこのエンドポイント経由で1回で取得できることを検証する。
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
}
