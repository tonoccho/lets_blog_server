package com.letsblog.publishing.controller;

import com.letsblog.publishing.cms.MediaUploadResult;
import com.letsblog.publishing.dto.MediaGcScanBridgeResponse;
import com.letsblog.publishing.service.ProjectNotFoundException;
import com.letsblog.publishing.testsupport.PublishingWorld;
import com.letsblog.publishing.testsupport.PublishingWorld.SiteSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CmsMediaBridgeControllerの回帰テスト(issue #573 stage3でlegacy-api向けに作成、issue #709で
 * publishing-serviceへ移管、Epic #551 C6-3)。media-serviceから移設されたMediaController/
 * MediaGarbageCollectionServiceのProject/Site解決ロジック(環境未設定時の例外を含む)が、
 * このブリッジコントローラへ正しく引き継がれていることを検証する。
 *
 * <p>issue #1816で書き直し: ProjectService・SiteService・CmsAdapterFactory・CmsAdapterは実物を使い、
 * 外部との通信(project-serviceへのHTTPは{@code FakeHttpServer}、WordPressへのSSHは{@code FakeWordPress})
 * だけを差し替える。
 */
class CmsMediaBridgeControllerTest {

    private static final SiteSpec LOCAL = SiteSpec.ssh(10L, "local-site", "ssh.example.com", "/var/www/html");
    private static final SiteSpec TEST = SiteSpec.ssh(11L, "test-site", "test.example.com", "/var/www/html");
    private static final SiteSpec PROD = SiteSpec.ssh(12L, "prod-site", "prod.example.com", "/var/www/html");

    private PublishingWorld world;
    private CmsMediaBridgeController controller;

    /** `wp eval`(メディア参照のスキャン)の応答。FakeWordPressはPHPを実行できないので、WordPressの出力を直接返す。 */
    private static final String SCAN_OUTPUT = "{\"posts\":[{\"id\":\"3\",\"postType\":\"post\",\"status\":\"publish\","
            + "\"content\":\"本文\",\"thumbnailId\":\"5\"}],\"settings\":{\"site_icon\":\"9\"}}";

    @BeforeEach
    void setUp() {
        world = new PublishingWorld();
        world.ssh.whenOk("eval", SCAN_OUTPUT);
        controller = new CmsMediaBridgeController(world.projectService, world.siteService, world.cmsAdapterFactory);
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    @Test
    void uploadMedia_CmsAdapter経由でアップロードする() throws Exception {
        world.stubCredentials("my-site", PublishingWorld.sshCredentialsMap());

        MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", "data".getBytes());
        MediaUploadResult result = controller.uploadMedia("my-site", file);

        assertEquals(1, world.ssh.media.size(), "WordPressへ1件アップロードされる");
        assertEquals(world.ssh.media.keySet().iterator().next(), result.id());
        assertTrue(result.url().endsWith("photo.jpg"), result.url());
        assertEquals("data", new String(world.ssh.media.values().iterator().next().bytes));
    }

    @Test
    void siteProjectId_サイトが属するプロジェクトのIDを返す() {
        world.withMainSite().bindProject(1L, 7L, null, null, 1L);

        assertEquals(7L, controller.siteProjectId("main").projectId());
    }

    @Test
    void siteProjectId_どの環境にも紐付いていなければnull() {
        world.withMainSite();

        assertNull(controller.siteProjectId("main").projectId());
    }

    @Test
    void scanMedia_プロジェクトのサイト情報からCMSデータを取得する() {
        world.bindEnvironments(1L, "test", LOCAL, TEST, PROD);
        world.ssh.site("ssh.example.com", "/var/www/html").addMedia("5", "https://local.test/a.jpg", "x".getBytes());

        MediaGcScanBridgeResponse response = controller.scanMedia(1L, "local");

        assertEquals(1, response.media().size());
        assertEquals("5", response.media().get(0).id());
        assertEquals("3", response.refs().posts().get(0).id());
        assertEquals("5", response.refs().posts().get(0).thumbnailId());
        assertEquals("9", response.refs().settingsMediaIds().get("site_icon"));
    }

    @Test
    void scanMedia_環境ごとに対応するサイトのメディアを読む() {
        world.bindEnvironments(1L, "test", LOCAL, TEST, PROD);
        world.ssh.site("test.example.com", "/var/www/html").addMedia("6", "https://test/b.jpg", "x".getBytes());
        world.ssh.site("prod.example.com", "/var/www/html").addMedia("7", "https://prod/c.jpg", "x".getBytes());

        assertEquals("6", controller.scanMedia(1L, "test").media().get(0).id());
        assertEquals("7", controller.scanMedia(1L, "production").media().get(0).id());
        assertTrue(controller.scanMedia(1L, "local").media().isEmpty(), "ローカルのサイトにはメディアが無い");
    }

    @Test
    void scanMedia_不正な環境名はIllegalArgumentException() {
        world.bindEnvironments(1L, "test", LOCAL, TEST, PROD);

        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> controller.scanMedia(1L, "staging"));

        assertTrue(e.getMessage().contains("staging"), e.getMessage());
    }

    @Test
    void scanMedia_環境にサイトが未設定なら例外() {
        world.bindEnvironments(1L, "test", null, TEST, PROD);
        world.bindProject(11L, 1L, null, 11L, 12L);

        assertThrows(IllegalArgumentException.class, () -> controller.scanMedia(1L, "local"));
    }

    @Test
    void scanMedia_環境のサイトがproject_serviceに無ければ例外() {
        world.bindEnvironments(1L, "test", LOCAL, TEST, PROD);
        world.project.status("GET", "/api/internal/project/sites/10", 404, "{}");

        assertThrows(IllegalArgumentException.class, () -> controller.scanMedia(1L, "local"));
    }

    @Test
    void scanMedia_存在しないプロジェクトはProjectNotFoundException() {
        assertThrows(ProjectNotFoundException.class, () -> controller.scanMedia(99L, "local"));
    }

    @Test
    void deleteMedia_CmsAdapter経由で削除し204を返す() {
        world.bindEnvironments(1L, "test", LOCAL, TEST, PROD);
        world.ssh.site("ssh.example.com", "/var/www/html").addMedia("42", "https://local.test/a.jpg", "x".getBytes());

        ResponseEntity<Void> response = controller.deleteMedia(1L, "42", "local");

        assertEquals(204, response.getStatusCode().value());
        assertFalse(world.ssh.site("ssh.example.com", "/var/www/html").media.containsKey("42"),
                "WordPressからメディアが消える");
    }
}
