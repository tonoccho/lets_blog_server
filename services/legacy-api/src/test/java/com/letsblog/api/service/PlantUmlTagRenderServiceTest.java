package com.letsblog.api.service;

import com.letsblog.api.ai.AiServiceException;
import com.letsblog.api.cms.CmsAdapter;
import com.letsblog.api.cms.CmsAdapterFactory;
import com.letsblog.api.cms.CmsCredentials;
import com.letsblog.api.cms.CmsType;
import com.letsblog.api.cms.MediaUploadResult;
import com.letsblog.api.render.MediaRenderClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

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
    private MediaRenderClient mediaRenderClient;
    @Mock
    private CmsAdapterFactory cmsAdapterFactory;
    @Mock
    private CmsAdapter cmsAdapter;

    private PlantUmlTagRenderService service;

    private final CmsCredentials.WordPressCredentials credentials =
            new CmsCredentials.WordPressCredentials("https://example.com", "admin", "SSH");

    @BeforeEach
    void setUp() {
        service = new PlantUmlTagRenderService(mediaRenderClient, cmsAdapterFactory);
        lenient().when(cmsAdapterFactory.resolve(CmsType.WORDPRESS)).thenReturn(cmsAdapter);
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

    @Test
    void render_複数タグをそれぞれ連番ファイル名でアップロードする() {
        when(mediaRenderClient.renderPlantUml(anyString())).thenReturn(new byte[]{1});
        when(cmsAdapter.uploadMedia(eq(credentials), eq("plantuml-tag-1.png"), eq("image/png"), any()))
                .thenReturn(new MediaUploadResult("10", "https://example.com/1.png"));
        when(cmsAdapter.uploadMedia(eq(credentials), eq("plantuml-tag-2.png"), eq("image/png"), any()))
                .thenReturn(new MediaUploadResult("11", "https://example.com/2.png"));

        String markdown = "[plantuml]\nA->B\n[/plantuml]\n本文\n[plantuml]\nC->D\n[/plantuml]";
        DiagramEmbedResult result = service.render(credentials, markdown, Map.of());

        assertEquals("![diagram](https://example.com/1.png)\n本文\n![diagram](https://example.com/2.png)",
                result.markdown());
        assertEquals(2, result.uploadedImages().size());
    }

    @Test
    void render_前回と同一内容のダイアグラムはメディアが実在すれば再アップロードしない() {
        // issue #499: 再投稿時に同じダイアグラムを何度も生成・アップロードしないための再利用判定。
        String wrapped = "@startuml\nA->B\n@enduml";
        String sha256 = sha256Hex(wrapped);
        Map<String, UploadedImageInfo> priorUploads = Map.of(
                "plantuml:" + sha256,
                new UploadedImageInfo(sha256, "https://example.com/cached.png", "10"));
        when(cmsAdapter.mediaExists(credentials, "10")).thenReturn(true);

        DiagramEmbedResult result = service.render(credentials, "[plantuml]\nA->B\n[/plantuml]", priorUploads);

        assertEquals("![diagram](https://example.com/cached.png)", result.markdown());
        verifyNoInteractions(mediaRenderClient);
        verify(cmsAdapter, never()).uploadMedia(any(), any(), any(), any());
    }

    @Test
    void render_内容が一致してもメディアがCMS側に実在しなければ再アップロードする() {
        String wrapped = "@startuml\nA->B\n@enduml";
        String sha256 = sha256Hex(wrapped);
        Map<String, UploadedImageInfo> priorUploads = Map.of(
                "plantuml:" + sha256,
                new UploadedImageInfo(sha256, "https://example.com/old.png", "10"));
        when(cmsAdapter.mediaExists(credentials, "10")).thenReturn(false);
        when(mediaRenderClient.renderPlantUml(wrapped)).thenReturn(new byte[]{1});
        when(cmsAdapter.uploadMedia(eq(credentials), eq("plantuml-tag-1.png"), eq("image/png"), any()))
                .thenReturn(new MediaUploadResult("11", "https://example.com/new.png"));

        DiagramEmbedResult result = service.render(credentials, "[plantuml]\nA->B\n[/plantuml]", priorUploads);

        assertEquals("![diagram](https://example.com/new.png)", result.markdown());
        verify(cmsAdapter).uploadMedia(eq(credentials), eq("plantuml-tag-1.png"), eq("image/png"), any());
    }

    @Test
    void render_アップロード対象が無ければPlantUMLサーバーへ問い合わせない() {
        service.render(credentials, "タグなしの本文です。", Map.of());

        verifyNoInteractions(mediaRenderClient);
    }

    @Test
    void render_レンダリング失敗時はInvalidPlantUmlTagExceptionを投げアップロードしない() {
        when(mediaRenderClient.renderPlantUml(anyString()))
                .thenThrow(new AiServiceException("PlantUMLサーバーに接続できません", null));

        assertThrows(InvalidPlantUmlTagException.class,
                () -> service.render(credentials, "[plantuml]\nA->B\n[/plantuml]", Map.of()));
        verify(cmsAdapter, never()).uploadMedia(any(), any(), any(), any());
    }

    private String sha256Hex(String source) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(
                    digest.digest(source.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
