package com.letsblog.content.service;

import com.letsblog.content.render.MediaRenderClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * legacy-apiのPlantUmlEmbedServiceTestのうち、content-serviceへ移設したプレビュー向け
 * (embedDiagramsForPreview、CMS認証情報を必要としない)の振る舞いを引き継いだテスト(issue #576)。
 */
@ExtendWith(MockitoExtension.class)
class PlantUmlEmbedServiceTest {

    @Mock
    private MediaRenderClient mediaRenderClient;

    private PlantUmlEmbedService service;

    @BeforeEach
    void setUp() {
        service = new PlantUmlEmbedService(mediaRenderClient);
    }

    @Test
    void embedDiagramsForPreview_data_URIとして埋め込みアップロードしない() {
        when(mediaRenderClient.renderPlantUml(any())).thenReturn(new byte[]{1, 2, 3});

        String result = service.embedDiagramsForPreview("```plantuml\nA->B\n```");

        assertTrue(result.contains("![diagram](data:image/png;base64,"));
    }
}
