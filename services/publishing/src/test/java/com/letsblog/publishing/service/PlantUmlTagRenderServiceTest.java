package com.letsblog.publishing.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import com.letsblog.publishing.cms.CmsCredentials;
import com.letsblog.publishing.render.PlantUmlRenderRejectedException;
import com.letsblog.publishing.testsupport.FakeHttpServer.Response;
import com.letsblog.publishing.testsupport.PublishingWorld;
import com.letsblog.publishing.testsupport.WpCliStubs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PlantUmlTagRenderServiceの回帰テスト(issue #344)。[plantuml]〜[/plantuml]タグの抽出、
 * 投稿向けCMSアップロード、エラー時の例外送出を検証する。
 *
 * <p>issue #1815で書き直し: MediaRenderClient・CmsAdapterFactory・WordPressAdapterは実物を使い、
 * media-serviceへのHTTP({@code FakeHttpServer})とWordPressへのSSH({@code FakeSshCommandExecutor})だけを差し替える。
 */
class PlantUmlTagRenderServiceTest {

    private PublishingWorld world;
    private PlantUmlTagRenderService service;

    private final CmsCredentials.WordPressCredentials credentials = PublishingWorld.sshCreds();

    @BeforeEach
    void setUp() {
        world = new PublishingWorld();
        service = new PlantUmlTagRenderService(world.mediaRenderClient, world.cmsAdapterFactory);
        world.media.on("POST", "/api/render/plantuml", Response.bytes(200, "image/png", new byte[]{1}));
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    @Test
    void render_複数タグをそれぞれ連番ファイル名でアップロードする() {
        WpCliStubs.mediaUploads(world.ssh, "10", "https://example.com/1.png", "11", "https://example.com/2.png");

        String markdown = "[plantuml]\nA->B\n[/plantuml]\n本文\n[plantuml]\nC->D\n[/plantuml]";
        DiagramEmbedResult result = service.render(credentials, markdown, Map.of());

        assertEquals("![diagram](https://example.com/1.png)\n本文\n![diagram](https://example.com/2.png)",
                result.markdown());
        assertEquals(2, result.uploadedImages().size());
        assertTrue(world.ssh.uploaded.keySet().stream().anyMatch(p -> p.endsWith("-plantuml-tag-1.png")));
        assertTrue(world.ssh.uploaded.keySet().stream().anyMatch(p -> p.endsWith("-plantuml-tag-2.png")));
    }

    @Test
    void render_前回と同一内容のダイアグラムはメディアが実在すれば再アップロードしない() {
        // issue #499: 再投稿時に同じダイアグラムを何度も生成・アップロードしないための再利用判定。
        String wrapped = "@startuml\nA->B\n@enduml";
        String sha256 = sha256Hex(wrapped);
        Map<String, UploadedImageInfo> priorUploads = Map.of(
                "plantuml:" + sha256,
                new UploadedImageInfo(sha256, "https://example.com/cached.png", "10"));
        world.ssh.whenOk("post get '10' --field=ID", "10");

        DiagramEmbedResult result = service.render(credentials, "[plantuml]\nA->B\n[/plantuml]", priorUploads);

        assertEquals("![diagram](https://example.com/cached.png)", result.markdown());
        assertTrue(world.media.requests().isEmpty(), "再生成しない");
        assertTrue(world.ssh.uploaded.isEmpty(), "再アップロードしない");
    }

    @Test
    void render_内容が一致してもメディアがCMS側に実在しなければ再アップロードする() {
        String wrapped = "@startuml\nA->B\n@enduml";
        String sha256 = sha256Hex(wrapped);
        Map<String, UploadedImageInfo> priorUploads = Map.of(
                "plantuml:" + sha256,
                new UploadedImageInfo(sha256, "https://example.com/old.png", "10"));
        world.ssh.whenFail("post get '10' --field=ID", "Error: Invalid post ID.");
        WpCliStubs.mediaUploads(world.ssh, "11", "https://example.com/new.png");

        DiagramEmbedResult result = service.render(credentials, "[plantuml]\nA->B\n[/plantuml]", priorUploads);

        assertEquals("![diagram](https://example.com/new.png)", result.markdown());
        assertTrue(world.ssh.uploaded.keySet().stream().anyMatch(p -> p.endsWith("-plantuml-tag-1.png")));
    }

    @Test
    void render_アップロード対象が無ければPlantUMLサーバーへ問い合わせない() {
        service.render(credentials, "タグなしの本文です。", Map.of());

        assertTrue(world.media.requests().isEmpty());
    }

    @Test
    void render_markdownが空ならそのまま返す() {
        DiagramEmbedResult nullResult = service.render(credentials, null, Map.of());
        DiagramEmbedResult emptyResult = service.render(credentials, "", Map.of());

        assertEquals(null, nullResult.markdown());
        assertEquals("", emptyResult.markdown());
        assertTrue(world.media.requests().isEmpty());
    }

    @Test
    void render_media呼び出し失敗時はPlantUmlServerUnavailableExceptionを投げアップロードしない() {
        world.media.status("POST", "/api/render/plantuml", 500, "{\"error\":\"down\"}");

        PlantUmlServerUnavailableException e = assertThrows(PlantUmlServerUnavailableException.class,
                () -> service.render(credentials, "[plantuml]\nA->B\n[/plantuml]", Map.of()));
        assertTrue(e.getMessage().contains("PlantUMLサーバーに接続できません"));
        assertTrue(world.ssh.uploaded.isEmpty());
    }

    @Test
    void render_PlantUMLの拒否は接続不能に変えずそのまま伝えアップロードしない() {
        world.media.status("POST", "/api/render/plantuml", 400,
                "{\"error\":\"PlantUML が図を描けませんでした: Syntax Error?\"}");

        PlantUmlRenderRejectedException e = assertThrows(PlantUmlRenderRejectedException.class,
                () -> service.render(credentials, "[plantuml]\nA->B\n[/plantuml]", Map.of()));

        assertTrue(e.getMessage().contains("Syntax Error?"));
        assertTrue(world.ssh.uploaded.isEmpty());
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

    @Test
    void render_既に_startumlを含む本文は包み直さずそのままレンダリングする() {
        WpCliStubs.mediaUploads(world.ssh, "10", "https://example.com/1.png");

        service.render(credentials, "[plantuml]\n@startuml\nA->B\n@enduml\n[/plantuml]", Map.of());

        String sent = world.media.last().bodyText();
        assertTrue(sent.contains("@startuml\\nA->B\\n@enduml"), sent);
        assertFalse(sent.contains("@startuml\\n@startuml"), sent);
    }
}
