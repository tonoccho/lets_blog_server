package com.letsblog.content.service;

import com.letsblog.content.client.AiServiceException;
import com.letsblog.content.render.MediaRenderClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * legacy-apiのPlantUmlTagRenderServiceTestのうち、content-serviceへ移設したプレビュー向け
 * (renderForPreview、CMS認証情報を必要としない)の振る舞いを引き継いだテスト(issue #576)。
 */
@ExtendWith(MockitoExtension.class)
class PlantUmlTagRenderServiceTest {

    @Mock
    private MediaRenderClient mediaRenderClient;

    private PlantUmlTagRenderService service;

    @BeforeEach
    void setUp() {
        service = new PlantUmlTagRenderService(mediaRenderClient);
    }

    @Test
    void renderForPreview_タグが無ければ何もしない() {
        assertEquals("普通の本文です。", service.renderForPreview("普通の本文です。"));
        verifyNoInteractions(mediaRenderClient);
    }

    @Test
    void renderForPreview_PNGをdata_URIとして埋め込む() {
        when(mediaRenderClient.renderPlantUml(anyString())).thenReturn(new byte[]{1, 2, 3});

        String result = service.renderForPreview("本文\n[plantuml]\n@startuml\nA->B\n@enduml\n[/plantuml]\n続き");

        assertTrue(result.contains("![diagram](data:image/png;base64,"));
        assertTrue(result.contains("続き"));
    }

    @Test
    void renderForPreview_startumlが無い場合は自動的に補う() {
        when(mediaRenderClient.renderPlantUml(anyString())).thenReturn(new byte[]{1});

        service.renderForPreview("[plantuml]\nA->B\n[/plantuml]");

        verify(mediaRenderClient).renderPlantUml(eq("@startuml\nA->B\n@enduml"));
    }

    @Test
    void renderForPreview_既にstartumlがある場合は二重に包まない() {
        when(mediaRenderClient.renderPlantUml(anyString())).thenReturn(new byte[]{1});

        service.renderForPreview("[plantuml]\n@startuml\nA->B\n@enduml\n[/plantuml]");

        verify(mediaRenderClient).renderPlantUml(eq("@startuml\nA->B\n@enduml"));
    }

    @Test
    void renderForPreview_レンダリング失敗時はInvalidPlantUmlTagExceptionを投げる() {
        when(mediaRenderClient.renderPlantUml(anyString()))
                .thenThrow(new AiServiceException("PlantUMLサーバーに接続できません", null));

        InvalidPlantUmlTagException e = assertThrows(InvalidPlantUmlTagException.class,
                () -> service.renderForPreview("[plantuml]\nA->B\n[/plantuml]"));
        assertTrue(e.getMessage().contains("PlantUMLサーバーに接続できません"));
    }
}
