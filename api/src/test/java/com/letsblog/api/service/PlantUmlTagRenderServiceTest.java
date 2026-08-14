package com.letsblog.api.service;

import com.letsblog.api.ai.AiServiceException;
import com.letsblog.api.cms.CmsAdapter;
import com.letsblog.api.cms.CmsAdapterFactory;
import com.letsblog.api.cms.CmsCredentials;
import com.letsblog.api.cms.CmsType;
import com.letsblog.api.cms.MediaUploadResult;
import com.letsblog.api.render.PlantUmlClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * PlantUmlTagRenderServiceの回帰テスト(issue #344)。[plantuml]〜[/plantuml]タグの抽出、
 * プレビュー向けdata URI埋め込み、投稿向けCMSアップロード、エラー時の
 * InvalidPlantUmlTagException送出を検証する。
 */
@ExtendWith(MockitoExtension.class)
class PlantUmlTagRenderServiceTest {

    @Mock
    private PlantUmlClient plantUmlClient;
    @Mock
    private CmsAdapterFactory cmsAdapterFactory;
    @Mock
    private CmsAdapter cmsAdapter;

    private PlantUmlTagRenderService service;

    private final CmsCredentials.WordPressCredentials credentials =
            new CmsCredentials.WordPressCredentials("https://example.com", "admin", "secret");

    @BeforeEach
    void setUp() {
        service = new PlantUmlTagRenderService(plantUmlClient, cmsAdapterFactory);
        lenient().when(cmsAdapterFactory.resolve(CmsType.WORDPRESS)).thenReturn(cmsAdapter);
    }

    @Test
    void renderForPreview_タグが無ければ何もしない() {
        assertEquals("普通の本文です。", service.renderForPreview("普通の本文です。"));
        verifyNoInteractions(plantUmlClient);
    }

    @Test
    void renderForPreview_PNGをdata_URIとして埋め込む() {
        when(plantUmlClient.renderPng(anyString())).thenReturn(new byte[]{1, 2, 3});

        String result = service.renderForPreview("本文\n[plantuml]\n@startuml\nA->B\n@enduml\n[/plantuml]\n続き");

        assertTrue(result.contains("![diagram](data:image/png;base64,"));
        assertTrue(result.contains("続き"));
    }

    @Test
    void renderForPreview_startumlが無い場合は自動的に補う() {
        when(plantUmlClient.renderPng(anyString())).thenReturn(new byte[]{1});

        service.renderForPreview("[plantuml]\nA->B\n[/plantuml]");

        verify(plantUmlClient).renderPng(eq("@startuml\nA->B\n@enduml"));
    }

    @Test
    void renderForPreview_既にstartumlがある場合は二重に包まない() {
        when(plantUmlClient.renderPng(anyString())).thenReturn(new byte[]{1});

        service.renderForPreview("[plantuml]\n@startuml\nA->B\n@enduml\n[/plantuml]");

        verify(plantUmlClient).renderPng(eq("@startuml\nA->B\n@enduml"));
    }

    @Test
    void renderForPreview_レンダリング失敗時はInvalidPlantUmlTagExceptionを投げる() {
        when(plantUmlClient.renderPng(anyString()))
                .thenThrow(new AiServiceException("PlantUMLサーバーに接続できません", null));

        InvalidPlantUmlTagException e = assertThrows(InvalidPlantUmlTagException.class,
                () -> service.renderForPreview("[plantuml]\nA->B\n[/plantuml]"));
        assertTrue(e.getMessage().contains("PlantUMLサーバーに接続できません"));
    }

    @Test
    void render_複数タグをそれぞれ連番ファイル名でアップロードする() {
        when(plantUmlClient.renderPng(anyString())).thenReturn(new byte[]{1});
        when(cmsAdapter.uploadMedia(eq(credentials), eq("plantuml-tag-1.png"), eq("image/png"), any()))
                .thenReturn(new MediaUploadResult("10", "https://example.com/1.png"));
        when(cmsAdapter.uploadMedia(eq(credentials), eq("plantuml-tag-2.png"), eq("image/png"), any()))
                .thenReturn(new MediaUploadResult("11", "https://example.com/2.png"));

        String markdown = "[plantuml]\nA->B\n[/plantuml]\n本文\n[plantuml]\nC->D\n[/plantuml]";
        String result = service.render(credentials, markdown);

        assertEquals("![diagram](https://example.com/1.png)\n本文\n![diagram](https://example.com/2.png)", result);
    }

    @Test
    void render_アップロード対象が無ければPlantUMLサーバーへ問い合わせない() {
        service.render(credentials, "タグなしの本文です。");

        verifyNoInteractions(plantUmlClient);
    }

    @Test
    void render_レンダリング失敗時はInvalidPlantUmlTagExceptionを投げアップロードしない() {
        when(plantUmlClient.renderPng(anyString()))
                .thenThrow(new AiServiceException("PlantUMLサーバーに接続できません", null));

        assertThrows(InvalidPlantUmlTagException.class,
                () -> service.render(credentials, "[plantuml]\nA->B\n[/plantuml]"));
        verify(cmsAdapter, never()).uploadMedia(any(), any(), any(), any());
    }
}
