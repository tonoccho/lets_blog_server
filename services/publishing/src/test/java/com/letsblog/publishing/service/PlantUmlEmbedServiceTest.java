package com.letsblog.publishing.service;

import com.letsblog.publishing.cms.CmsCredentials;
import com.letsblog.publishing.testsupport.FakeHttpServer.Response;
import com.letsblog.publishing.testsupport.PublishingWorld;
import com.letsblog.publishing.testsupport.WpCliStubs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PlantUmlEmbedServiceの回帰テスト。```plantumlフェンスコードブロックの抽出・アップロードに加え、
 * 同一内容のダイアグラムを再投稿時に再生成・再アップロードしない再利用キャッシュ(issue #499)を検証する。
 *
 * <p>issue #1815で書き直し: MediaRenderClient・CmsAdapterFactory・WordPressAdapterは実物を使い、
 * media-serviceへのHTTP({@code FakeHttpServer})とWordPressへのSSH({@code FakeSshCommandExecutor})だけを差し替える。
 */
class PlantUmlEmbedServiceTest {

    private PublishingWorld world;
    private PlantUmlEmbedService service;

    private final CmsCredentials.WordPressCredentials credentials = PublishingWorld.sshCreds();

    @BeforeEach
    void setUp() {
        world = new PublishingWorld();
        service = new PlantUmlEmbedService(world.mediaRenderClient, world.cmsAdapterFactory);
        world.media.on("POST", "/api/render/plantuml", Response.bytes(200, "image/png", new byte[]{1}));
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    @Test
    void embedDiagrams_ブロックが無ければ何もしない() {
        DiagramEmbedResult result = service.embedDiagrams(credentials, "普通の本文です。", Map.of());

        assertEquals("普通の本文です。", result.markdown());
        assertTrue(world.media.requests().isEmpty(), "PlantUMLのレンダリングは呼ばれない");
    }

    @Test
    void embedDiagrams_複数ブロックをそれぞれ連番ファイル名でアップロードする() {
        WpCliStubs.mediaUploads(world.ssh, "10", "https://example.com/1.png", "11", "https://example.com/2.png");

        String markdown = "```plantuml\nA->B\n```\n本文\n```plantuml\nC->D\n```";
        DiagramEmbedResult result = service.embedDiagrams(credentials, markdown, Map.of());

        assertEquals("![diagram](https://example.com/1.png)\n本文\n![diagram](https://example.com/2.png)",
                result.markdown());
        assertEquals(2, result.uploadedImages().size());
        assertEquals(2, world.media.requests("POST", "/api/render/plantuml").size());
        assertTrue(world.ssh.uploaded.keySet().stream().anyMatch(p -> p.endsWith("-plantuml-1.png")));
        assertTrue(world.ssh.uploaded.keySet().stream().anyMatch(p -> p.endsWith("-plantuml-2.png")));
    }

    @Test
    void embedDiagrams_前回と同一内容のダイアグラムはメディアが実在すれば再アップロードしない() {
        // issue #499: 再投稿時に同じダイアグラムを何度も生成・アップロードしないための再利用判定。
        String wrapped = "@startuml\nA->B\n@enduml";
        String sha256 = sha256Hex(wrapped);
        Map<String, UploadedImageInfo> priorUploads = Map.of(
                "plantuml:" + sha256,
                new UploadedImageInfo(sha256, "https://example.com/cached.png", "10"));
        world.ssh.whenOk("post get '10' --field=ID", "10");

        DiagramEmbedResult result = service.embedDiagrams(credentials, "```plantuml\nA->B\n```", priorUploads);

        assertEquals("![diagram](https://example.com/cached.png)", result.markdown());
        assertTrue(world.media.requests().isEmpty(), "再生成しない");
        assertTrue(world.ssh.uploaded.isEmpty(), "再アップロードしない");
    }

    @Test
    void embedDiagrams_内容が一致してもメディアがCMS側に実在しなければ再アップロードする() {
        String wrapped = "@startuml\nA->B\n@enduml";
        String sha256 = sha256Hex(wrapped);
        Map<String, UploadedImageInfo> priorUploads = Map.of(
                "plantuml:" + sha256,
                new UploadedImageInfo(sha256, "https://example.com/old.png", "10"));
        world.ssh.whenFail("post get '10' --field=ID", "Error: Invalid post ID.");
        WpCliStubs.mediaUploads(world.ssh, "11", "https://example.com/new.png");

        DiagramEmbedResult result = service.embedDiagrams(credentials, "```plantuml\nA->B\n```", priorUploads);

        assertEquals("![diagram](https://example.com/new.png)", result.markdown());
        assertTrue(world.media.last().bodyText().contains("A->B"));
        assertFalse(world.ssh.uploaded.isEmpty(), "再アップロードされる");
    }

    @Test
    void embedDiagrams_既にstartumlを含むブロックは包み直さずそのままレンダリングする() {
        WpCliStubs.mediaUploads(world.ssh, "10", "https://example.com/1.png");

        service.embedDiagrams(credentials, "```plantuml\n@startuml\nA->B\n@enduml\n```", Map.of());

        String sent = world.media.last().bodyText();
        assertTrue(sent.contains("@startuml\\nA->B\\n@enduml"), sent);
        assertFalse(sent.contains("@startuml\\n@startuml"), sent);
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
