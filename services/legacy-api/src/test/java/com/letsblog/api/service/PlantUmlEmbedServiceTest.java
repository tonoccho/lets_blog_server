package com.letsblog.api.service;

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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * PlantUmlEmbedServiceの回帰テスト。```plantumlフェンスコードブロックの抽出・アップロードに加え、
 * 同一内容のダイアグラムを再投稿時に再生成・再アップロードしない再利用キャッシュ(issue #499)を検証する。
 */
@ExtendWith(MockitoExtension.class)
class PlantUmlEmbedServiceTest {

    @Mock
    private MediaRenderClient mediaRenderClient;
    @Mock
    private CmsAdapterFactory cmsAdapterFactory;
    @Mock
    private CmsAdapter cmsAdapter;

    private PlantUmlEmbedService service;

    private final CmsCredentials.WordPressCredentials credentials =
            new CmsCredentials.WordPressCredentials("https://example.com", "admin", "SSH");

    @BeforeEach
    void setUp() {
        service = new PlantUmlEmbedService(mediaRenderClient, cmsAdapterFactory);
        lenient().when(cmsAdapterFactory.resolve(CmsType.WORDPRESS)).thenReturn(cmsAdapter);
    }

    @Test
    void embedDiagrams_ブロックが無ければ何もしない() {
        DiagramEmbedResult result = service.embedDiagrams(credentials, "普通の本文です。", Map.of());

        assertEquals("普通の本文です。", result.markdown());
        verifyNoInteractions(mediaRenderClient);
    }

    @Test
    void embedDiagrams_複数ブロックをそれぞれ連番ファイル名でアップロードする() {
        when(mediaRenderClient.renderPlantUml(any())).thenReturn(new byte[]{1});
        when(cmsAdapter.uploadMedia(eq(credentials), eq("plantuml-1.png"), eq("image/png"), any()))
                .thenReturn(new MediaUploadResult("10", "https://example.com/1.png"));
        when(cmsAdapter.uploadMedia(eq(credentials), eq("plantuml-2.png"), eq("image/png"), any()))
                .thenReturn(new MediaUploadResult("11", "https://example.com/2.png"));

        String markdown = "```plantuml\nA->B\n```\n本文\n```plantuml\nC->D\n```";
        DiagramEmbedResult result = service.embedDiagrams(credentials, markdown, Map.of());

        assertEquals("![diagram](https://example.com/1.png)\n本文\n![diagram](https://example.com/2.png)",
                result.markdown());
        assertEquals(2, result.uploadedImages().size());
    }

    @Test
    void embedDiagrams_前回と同一内容のダイアグラムはメディアが実在すれば再アップロードしない() {
        // issue #499: 再投稿時に同じダイアグラムを何度も生成・アップロードしないための再利用判定。
        String wrapped = "@startuml\nA->B\n@enduml";
        String sha256 = sha256Hex(wrapped);
        Map<String, UploadedImageInfo> priorUploads = Map.of(
                "plantuml:" + sha256,
                new UploadedImageInfo(sha256, "https://example.com/cached.png", "10"));
        when(cmsAdapter.mediaExists(credentials, "10")).thenReturn(true);

        DiagramEmbedResult result = service.embedDiagrams(credentials, "```plantuml\nA->B\n```", priorUploads);

        assertEquals("![diagram](https://example.com/cached.png)", result.markdown());
        verifyNoInteractions(mediaRenderClient);
        verify(cmsAdapter, never()).uploadMedia(any(), any(), any(), any());
    }

    @Test
    void embedDiagrams_内容が一致してもメディアがCMS側に実在しなければ再アップロードする() {
        String wrapped = "@startuml\nA->B\n@enduml";
        String sha256 = sha256Hex(wrapped);
        Map<String, UploadedImageInfo> priorUploads = Map.of(
                "plantuml:" + sha256,
                new UploadedImageInfo(sha256, "https://example.com/old.png", "10"));
        when(cmsAdapter.mediaExists(credentials, "10")).thenReturn(false);
        when(mediaRenderClient.renderPlantUml(wrapped)).thenReturn(new byte[]{1});
        when(cmsAdapter.uploadMedia(eq(credentials), eq("plantuml-1.png"), eq("image/png"), any()))
                .thenReturn(new MediaUploadResult("11", "https://example.com/new.png"));

        DiagramEmbedResult result = service.embedDiagrams(credentials, "```plantuml\nA->B\n```", priorUploads);

        assertEquals("![diagram](https://example.com/new.png)", result.markdown());
        verify(cmsAdapter).uploadMedia(eq(credentials), eq("plantuml-1.png"), eq("image/png"), any());
    }

    @Test
    void embedDiagramsForPreview_data_URIとして埋め込みアップロードしない() {
        when(mediaRenderClient.renderPlantUml(any())).thenReturn(new byte[]{1, 2, 3});

        String result = service.embedDiagramsForPreview("```plantuml\nA->B\n```");

        assertTrue(result.contains("![diagram](data:image/png;base64,"));
        verifyNoInteractions(cmsAdapterFactory);
    }

    private String sha256Hex(String source) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
