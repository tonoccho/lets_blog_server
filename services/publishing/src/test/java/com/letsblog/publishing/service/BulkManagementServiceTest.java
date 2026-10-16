package com.letsblog.publishing.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.letsblog.publishing.cms.CmsType;
import com.letsblog.publishing.domain.BulkOperationLog;
import com.letsblog.publishing.domain.BulkOperationSourceType;
import com.letsblog.publishing.domain.BulkOperationStatus;
import com.letsblog.publishing.domain.BulkOperationType;
import com.letsblog.publishing.domain.Site;
import com.letsblog.publishing.provisioning.WordPressBulkManagementClient.BulkApplyCommand;
import com.letsblog.publishing.testsupport.FakeHttpServer.Response;
import com.letsblog.publishing.testsupport.FakeWordPress;
import com.letsblog.publishing.testsupport.PublishingWorld;
import com.letsblog.publishing.testsupport.PublishingWorld.SiteSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BulkManagementServiceの回帰テスト。
 *
 * <p>issue #1815で書き直し: 自前のクラス(SiteService・ProjectService・ProjectServiceClient・
 * WordPressBulkManagementClient・WordPressSshOperations・CmsAdapterFactory・WordPressAdapter・
 * BulkUploadStorageService・ImageResizeService・MediaSettingsBridgeClient)は実物を使い、外部との通信だけを
 * 差し替える。HTTP(project-service・media-service・provision-agent)は{@code FakeHttpServer}、WordPressへのSSH
 * (wp-cli)は{@code FakeWordPress}。managed環境への操作は、provision-agentが実際に受け取った要求で確かめる。
 */
class BulkManagementServiceTest {

    private PublishingWorld world;
    private BulkManagementService service;

    @BeforeEach
    void setUp() {
        world = new PublishingWorld();
        service = world.bulkManagementService;
        world.agent.json("POST", "/bulk-management", "{\"status\":\"success\"}");
        world.agent.json("POST", "/bulk-management/upload", "{\"status\":\"success\"}");
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    // ---- 部品 ----

    private static final String EXT_HOST = "203.0.113.5";

    private SiteSpec managed(long id, String slug) {
        return SiteSpec.managed(id, slug);
    }

    private SiteSpec external(long id, String slug) {
        return SiteSpec.noSsh(id, slug);
    }

    private SiteSpec ssh(long id, String slug, String host) {
        return SiteSpec.ssh(id, slug, host, "/var/www/" + slug);
    }

    private void bind(SiteSpec local, SiteSpec test, SiteSpec production) {
        world.bindEnvironments(1L, "test", local, test, production);
    }

    private void bindThreeManagedSites() {
        bind(managed(10L, "local-site"), managed(20L, "test-site"), managed(30L, "production-site"));
    }

    private List<JsonNode> applied() {
        return world.agent.requests("POST", "/bulk-management").stream().map(r -> {
            try {
                return world.mapper.readTree(r.body());
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }).toList();
    }

    private boolean wasApplied(String slug, String action, String value, String categorySlug, String parentSlug,
            String description, String targetSlug) {
        JsonNode expected = world.mapper.valueToTree(
                new BulkApplyCommand(slug, action, value, categorySlug, parentSlug, description, targetSlug));
        return applied().contains(expected);
    }

    private boolean wasApplied(String slug, String action, String value) {
        return wasApplied(slug, action, value, null, null, null, null);
    }

    private static Site siteOf(long id, String key) {
        Site site = new Site();
        site.setId(id);
        site.setSiteKey(key);
        site.setCmsType(CmsType.WORDPRESS);
        return site;
    }

    private byte[] png(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static int[] sizeOf(byte[] image) throws IOException {
        BufferedImage read = ImageIO.read(new ByteArrayInputStream(image));
        return new int[] {read.getWidth(), read.getHeight()};
    }

    // ---- applyToEnvironment: CATEGORY_CREATE ----

    @Test
    void applyToEnvironment_categoryCreate_スラッグ未指定は例外() {
        assertThrows(IllegalArgumentException.class, () -> service.applyToEnvironment(
                1L, "local", BulkOperationType.CATEGORY_CREATE, "お知らせ", null, null, null, null, 9L));
    }

    @Test
    void applyToEnvironment_categoryCreate_環境にサイトが紐付いていなければ例外() {
        bind(null, null, null);
        world.bindProject(10L, 1L, null, null, null, "test");

        assertThrows(IllegalArgumentException.class, () -> service.applyToEnvironment(
                1L, "local", BulkOperationType.CATEGORY_CREATE, "お知らせ", "oshirase", null, null, null, 9L));
        assertTrue(applied().isEmpty());
    }

    @Test
    void applyToEnvironment_categoryCreate_環境名が不正なら例外() {
        bind(managed(10L, "local-site"), null, null);

        assertThrows(IllegalArgumentException.class, () -> service.applyToEnvironment(
                1L, "staging", BulkOperationType.CATEGORY_CREATE, "お知らせ", "oshirase", null, null, null, 9L));
    }

    @Test
    void applyToEnvironment_categoryCreate_登録されていないサイトはSiteNotFoundException() {
        world.bindProject(10L, 1L, 10L, null, null, "test");

        assertThrows(SiteNotFoundException.class, () -> service.applyToEnvironment(
                1L, "local", BulkOperationType.CATEGORY_CREATE, "お知らせ", "oshirase", null, null, null, 9L));
    }

    @Test
    void applyToEnvironment_categoryCreate_非managedサイトの環境は例外() {
        bind(external(10L, "external-site"), null, null);

        assertThrows(IllegalArgumentException.class, () -> service.applyToEnvironment(
                1L, "local", BulkOperationType.CATEGORY_CREATE, "お知らせ", "oshirase", null, null, null, 9L));
        assertTrue(applied().isEmpty());
    }

    @Test
    void applyToEnvironment_categoryCreate_指定した環境のみへ適用してログを保存する() {
        bind(managed(10L, "local-site"), managed(20L, "test-site"), null);

        BulkOperationLog result = service.applyToEnvironment(
                1L, "local", BulkOperationType.CATEGORY_CREATE, "お知らせ", "oshirase", null, null, null, 9L);

        assertEquals("local", result.getEnvironment());
        assertEquals(BulkOperationStatus.SUCCESS, result.getStatus());
        assertEquals(BulkOperationSourceType.SLUG, result.getSourceType());
        assertEquals("oshirase", result.getCategorySlug());
        assertTrue(wasApplied("local-site", "category_create", "お知らせ", "oshirase", null, null, null));
        assertEquals(1, applied().size(), "指定した環境だけに適用する");
        assertTrue(world.project.requests("GET", "/api/internal/project/sites/20").isEmpty());
    }

    @Test
    void applyToEnvironment_categoryCreate_親カテゴリと説明を渡せる() {
        bind(managed(10L, "local-site"), null, null);

        BulkOperationLog result = service.applyToEnvironment(
                1L, "local", BulkOperationType.CATEGORY_CREATE, "サブお知らせ", "sub-oshirase", "oshirase", "説明文",
                null, 9L);

        assertEquals("sub-oshirase", result.getCategorySlug());
        assertEquals("oshirase", result.getCategoryParentSlug());
        assertEquals("説明文", result.getCategoryDescription());
        assertTrue(wasApplied("local-site", "category_create", "サブお知らせ", "sub-oshirase", "oshirase", "説明文", null));
    }

    @Test
    void applyToEnvironment_失敗結果もそのまま記録される() {
        bind(managed(10L, "local-site"), null, null);
        world.agent.status("POST", "/bulk-management", 502, "{\"error\":\"接続に失敗しました\"}");

        BulkOperationLog result = service.applyToEnvironment(
                1L, "local", BulkOperationType.PLUGIN_INSTALL, "akismet", null, null, null, null, 9L);

        assertEquals(BulkOperationStatus.FAILED, result.getStatus());
        assertTrue(result.getErrorMessage().contains("provision-agentがエラー応答を返しました"), result.getErrorMessage());
        assertNotNull(result.getStackTrace());
    }

    @Test
    void applyToEnvironment_SKIPPEDもそのまま記録される() {
        bind(managed(10L, "local-site"), null, null);
        world.agent.json("POST", "/bulk-management", "{\"status\":\"skipped\"}");

        BulkOperationLog result = service.applyToEnvironment(
                1L, "local", BulkOperationType.THEME_INSTALL, "twentytwentyfour", null, null, null, null, 9L);

        assertEquals(BulkOperationStatus.SKIPPED, result.getStatus());
        assertTrue(wasApplied("local-site", "theme_install", "twentytwentyfour"));
    }

    @Test
    void applyToEnvironment_プラグイン系はslug未指定で例外() {
        assertThrows(IllegalArgumentException.class, () -> service.applyToEnvironment(
                1L, "local", BulkOperationType.PLUGIN_ACTIVATE, "", null, null, null, null, 9L));
    }

    @Test
    void applyToEnvironment_プラグイン有効化_無効化_削除がそれぞれのactionで呼ばれる() {
        bind(managed(10L, "local-site"), null, null);

        service.applyToEnvironment(1L, "local", BulkOperationType.PLUGIN_ACTIVATE, "akismet", null, null, null, null, 9L);
        service.applyToEnvironment(1L, "local", BulkOperationType.PLUGIN_DEACTIVATE, "akismet", null, null, null, null, 9L);
        service.applyToEnvironment(1L, "local", BulkOperationType.PLUGIN_DELETE, "akismet", null, null, null, null, 9L);

        assertTrue(wasApplied("local-site", "plugin_activate", "akismet"));
        assertTrue(wasApplied("local-site", "plugin_deactivate", "akismet"));
        assertTrue(wasApplied("local-site", "plugin_delete", "akismet"));
    }

    @Test
    void applyToEnvironment_テーマ有効化_削除がそれぞれのactionで呼ばれる() {
        bind(managed(10L, "local-site"), null, null);

        service.applyToEnvironment(1L, "local", BulkOperationType.THEME_ACTIVATE, "twentytwentyfour", null, null, null, null, 9L);
        service.applyToEnvironment(1L, "local", BulkOperationType.THEME_DELETE, "twentytwentyfour", null, null, null, null, 9L);

        assertTrue(wasApplied("local-site", "theme_activate", "twentytwentyfour"));
        assertTrue(wasApplied("local-site", "theme_delete", "twentytwentyfour"));
    }

    // ---- applyToEnvironment: CATEGORY_EDIT / CATEGORY_DELETE / TAG_* ----

    @Test
    void applyToEnvironment_categoryEdit_targetSlug未指定は例外() {
        assertThrows(IllegalArgumentException.class, () -> service.applyToEnvironment(
                1L, "local", BulkOperationType.CATEGORY_EDIT, "新名前", "new-slug", null, null, null, 9L));
    }

    @Test
    void applyToEnvironment_categoryEdit_正しいコマンドで呼ばれる() {
        bind(managed(10L, "local-site"), null, null);

        BulkOperationLog result = service.applyToEnvironment(
                1L, "local", BulkOperationType.CATEGORY_EDIT, "新お知らせ", "new-oshirase", "parent-slug", "更新後の説明",
                "old-oshirase", 9L);

        assertEquals(BulkOperationStatus.SUCCESS, result.getStatus());
        assertEquals("old-oshirase", result.getCategoryTargetSlug());
        assertTrue(wasApplied("local-site", "category_edit", "新お知らせ", "new-oshirase", "parent-slug",
                "更新後の説明", "old-oshirase"));
    }

    @Test
    void applyToEnvironment_categoryDelete_targetSlug未指定は例外() {
        assertThrows(IllegalArgumentException.class, () -> service.applyToEnvironment(
                1L, "local", BulkOperationType.CATEGORY_DELETE, null, null, null, null, null, 9L));
    }

    @Test
    void applyToEnvironment_categoryEdit_名前とスラッグが無ければ例外() {
        assertThrows(IllegalArgumentException.class, () -> service.applyToEnvironment(
                1L, "local", BulkOperationType.CATEGORY_EDIT, " ", "new-slug", null, null, "old", 9L));
        assertThrows(IllegalArgumentException.class, () -> service.applyToEnvironment(
                1L, "local", BulkOperationType.TAG_EDIT, "新名前", " ", null, null, "old", 9L));
    }

    @Test
    void applyToEnvironment_categoryDelete_valueが空でもtargetSlugで補われる() {
        bind(managed(10L, "local-site"), null, null);

        BulkOperationLog result = service.applyToEnvironment(
                1L, "local", BulkOperationType.CATEGORY_DELETE, null, null, null, null, "oshirase", 9L);

        assertEquals("oshirase", result.getValue());
        assertEquals("oshirase", result.getCategoryTargetSlug());
        assertTrue(wasApplied("local-site", "category_delete", "oshirase", null, null, null, "oshirase"));
    }

    @Test
    void applyToEnvironment_categoryDelete_valueが指定されていればそのまま使う() {
        bind(managed(10L, "local-site"), null, null);

        BulkOperationLog result = service.applyToEnvironment(
                1L, "local", BulkOperationType.TAG_DELETE, "お知らせ", null, null, null, "oshirase", 9L);

        assertEquals("お知らせ", result.getValue());
    }

    @Test
    void applyToEnvironment_tagCreate_正しいactionで呼ばれる() {
        bind(managed(10L, "local-site"), null, null);

        BulkOperationLog result = service.applyToEnvironment(
                1L, "local", BulkOperationType.TAG_CREATE, "新着", "shinchaku", null, null, null, 9L);

        assertEquals(BulkOperationStatus.SUCCESS, result.getStatus());
        assertEquals("shinchaku", result.getCategorySlug());
        assertTrue(wasApplied("local-site", "tag_create", "新着", "shinchaku", null, null, null));
    }

    // ---- applyToAllEnvironments (issue #393) ----

    @Test
    void applyToAllEnvironments_カテゴリ系は例外() {
        assertThrows(IllegalArgumentException.class, () -> service.applyToAllEnvironments(
                1L, BulkOperationType.CATEGORY_CREATE, "お知らせ", 9L));
    }

    @Test
    void applyToAllEnvironments_有効化等のアクションは例外() {
        assertThrows(IllegalArgumentException.class, () -> service.applyToAllEnvironments(
                1L, BulkOperationType.PLUGIN_ACTIVATE, "akismet", 9L));
    }

    @Test
    void applyToAllEnvironments_slug未指定は例外() {
        assertThrows(IllegalArgumentException.class, () -> service.applyToAllEnvironments(
                1L, BulkOperationType.PLUGIN_INSTALL, "", 9L));
    }

    @Test
    void applyToAllEnvironments_紐付いている全環境へインストールしログを保存する() {
        bindThreeManagedSites();

        List<BulkOperationLog> results = service.applyToAllEnvironments(
                1L, BulkOperationType.PLUGIN_INSTALL, "akismet", 9L);

        assertEquals(3, results.size());
        assertEquals("local", results.get(0).getEnvironment());
        assertEquals("test", results.get(1).getEnvironment());
        assertEquals("production", results.get(2).getEnvironment());
        results.forEach(r -> assertEquals(BulkOperationStatus.SUCCESS, r.getStatus()));
        assertTrue(wasApplied("local-site", "plugin_install", "akismet"));
        assertTrue(wasApplied("test-site", "plugin_install", "akismet"));
        assertTrue(wasApplied("production-site", "plugin_install", "akismet"));
    }

    @Test
    void applyToAllEnvironments_紐付いていない環境はスキップする() {
        bind(managed(10L, "local-site"), null, null);

        List<BulkOperationLog> results = service.applyToAllEnvironments(
                1L, BulkOperationType.PLUGIN_INSTALL, "akismet", 9L);

        assertEquals(1, results.size());
        assertEquals("local", results.get(0).getEnvironment());
    }

    @Test
    void applyToAllEnvironments_1環境が対象外でも他環境の実行を止めずFAILEDとして記録する() {
        bind(managed(10L, "local-site"), external(20L, "external-site"), null);

        List<BulkOperationLog> results = service.applyToAllEnvironments(
                1L, BulkOperationType.PLUGIN_INSTALL, "akismet", 9L);

        assertEquals(2, results.size());
        assertEquals(BulkOperationStatus.SUCCESS, results.get(0).getStatus());
        assertEquals("local", results.get(0).getEnvironment());
        assertEquals(BulkOperationStatus.FAILED, results.get(1).getStatus());
        assertEquals("test", results.get(1).getEnvironment());
        assertFalse(wasApplied("external-site", "plugin_install", "akismet"));
    }

    // ---- executeFromUpload ----

    @Test
    void executeFromUpload_カテゴリ系は例外() {
        MockMultipartFile file = new MockMultipartFile("file", "theme.zip", "application/zip", new byte[]{1});

        assertThrows(IllegalArgumentException.class,
                () -> service.executeFromUpload(1L, BulkOperationType.CATEGORY_CREATE, file, 9L));
    }

    @Test
    void executeFromUpload_有効化等のアクションは例外() {
        MockMultipartFile file = new MockMultipartFile("file", "theme.zip", "application/zip", new byte[]{1});

        assertThrows(IllegalArgumentException.class,
                () -> service.executeFromUpload(1L, BulkOperationType.PLUGIN_ACTIVATE, file, 9L));
    }

    @Test
    void executeFromUpload_拡張子がzipでなければ例外() {
        MockMultipartFile file = new MockMultipartFile("file", "theme.txt", "text/plain", new byte[]{1});

        assertThrows(IllegalArgumentException.class,
                () -> service.executeFromUpload(1L, BulkOperationType.THEME_INSTALL, file, 9L));
    }

    private List<com.letsblog.publishing.testsupport.FakeHttpServer.Request> zipUploads() {
        return world.agent.requests("POST", "/bulk-management/upload");
    }

    @Test
    void executeFromUpload_保存してzipを各環境へ転送しログを保存する() throws IOException {
        bind(managed(10L, "local-site"), managed(20L, "test-site"), null);
        byte[] content = new byte[]{1, 2, 3};
        MockMultipartFile file = new MockMultipartFile("file", "custom-theme.zip", "application/zip", content);

        List<BulkOperationLog> results = service.executeFromUpload(1L, BulkOperationType.THEME_INSTALL, file, 9L);

        String sha256 = com.letsblog.publishing.cms.MediaContentHash.sha256Hex(content);
        assertEquals(2, results.size());
        assertEquals(BulkOperationSourceType.ZIP, results.get(0).getSourceType());
        assertEquals("custom-theme.zip", results.get(0).getOriginalFilename());
        assertEquals("1/" + sha256 + ".zip", results.get(0).getStoragePath());
        assertEquals(sha256, results.get(0).getFileSha256());
        assertArrayEquals(content, world.bulkUploadStorageService.load("1/" + sha256 + ".zip"));
        assertEquals(2, zipUploads().size());
        String body = zipUploads().stream().map(r -> r.bodyText()).reduce("", String::concat);
        assertTrue(body.contains("local-site") && body.contains("test-site"), body);
        assertTrue(body.contains("theme_install") && body.contains("custom-theme.zip"), body);
    }

    private static void assertArrayEquals(byte[] expected, byte[] actual) {
        org.junit.jupiter.api.Assertions.assertArrayEquals(expected, actual);
    }

    @Test
    void executeFromUpload_SSH接続情報のある非managed環境にもSFTP転送で適用する() throws IOException {
        bind(managed(10L, "local-site"), ssh(20L, "external-site", EXT_HOST), null);
        MockMultipartFile file = new MockMultipartFile("file", "custom-theme.zip", "application/zip", new byte[]{1, 2, 3});

        List<BulkOperationLog> results = service.executeFromUpload(1L, BulkOperationType.THEME_INSTALL, file, 9L);

        assertEquals(2, results.size());
        assertEquals(1, zipUploads().size(), "managed環境へはprovision-agentへ");
        FakeWordPress.Site remote = world.ssh.site(EXT_HOST, "/var/www/external-site");
        assertTrue(remote.themes.containsKey("custom-theme"), "SSH環境へはwp theme installで: " + remote.themes);
        assertTrue(world.ssh.commands.stream().anyMatch(c -> c.contains("'theme' install") && c.contains("--force")));
        results.forEach(r -> assertEquals(BulkOperationStatus.SUCCESS, r.getStatus()));
    }

    @Test
    void executeFromUpload_SSHが無い非managed環境は対象外() throws IOException {
        bind(managed(10L, "local-site"), external(20L, "external-site"), null);
        MockMultipartFile file = new MockMultipartFile("file", "custom-theme.zip", "application/zip", new byte[]{1, 2, 3});

        List<BulkOperationLog> results = service.executeFromUpload(1L, BulkOperationType.THEME_INSTALL, file, 9L);

        assertEquals(1, results.size());
        assertEquals("local", results.get(0).getEnvironment());
        assertTrue(world.ssh.commands.isEmpty());
    }

    @Test
    void executeFromUpload_紐付けられたサイトが登録されていない環境は対象外() throws IOException {
        bind(managed(10L, "local-site"), null, null);
        world.bindProject(10L, 1L, 10L, 99L, null, "test");
        MockMultipartFile file = new MockMultipartFile("file", "custom-theme.zip", "application/zip", new byte[]{1});

        List<BulkOperationLog> results = service.executeFromUpload(1L, BulkOperationType.THEME_INSTALL, file, 9L);

        assertEquals(List.of("local"), results.stream().map(BulkOperationLog::getEnvironment).toList());
    }

    @Test
    void executeFromUpload_SSHへの転送が失敗した環境はFAILEDとして記録される() throws IOException {
        bind(ssh(10L, "local-site", "ssh-local"), null, null);
        world.ssh.rejectHost("ssh-local");
        MockMultipartFile file = new MockMultipartFile("file", "custom-theme.zip", "application/zip", new byte[]{1, 2, 3});

        List<BulkOperationLog> results = service.executeFromUpload(1L, BulkOperationType.THEME_INSTALL, file, 9L);

        assertEquals(BulkOperationStatus.FAILED, results.get(0).getStatus());
        assertTrue(results.get(0).getErrorMessage().contains("ssh-local"), results.get(0).getErrorMessage());
    }

    // ---- uploadImageToAllEnvironments ----

    @Test
    void uploadImageToAllEnvironments_紐付いた環境ごとにアップロードしログを記録する() {
        bind(ssh(10L, "local-site", "ssh-local"), ssh(20L, "test-site", "ssh-test"), null);
        world.ssh.rejectHost("ssh-test");
        byte[] originalData = new byte[]{1, 2, 3};

        List<BulkOperationLog> results = service.uploadImageToAllEnvironments(
                1L, originalData, "cat.png", "image/png", 9L);

        assertEquals(2, results.size());
        assertEquals("local", results.get(0).getEnvironment());
        assertEquals(BulkOperationStatus.SUCCESS, results.get(0).getStatus());
        assertTrue(results.get(0).getValue().endsWith("-cat.png"), results.get(0).getValue());
        assertEquals("test", results.get(1).getEnvironment());
        assertEquals(BulkOperationStatus.FAILED, results.get(1).getStatus());
        assertTrue(results.get(1).getErrorMessage().contains("接続に失敗しました"), results.get(1).getErrorMessage());
        FakeWordPress.Site local = world.ssh.site("ssh-local", "/var/www/local-site");
        assertEquals(1, local.media.size());
        assertArrayEquals(originalData, local.media.values().iterator().next().bytes);
        assertEquals(1, world.media.requests("GET", "/api/internal/media/projects/1/article-image-long-edge-px").size());
    }

    @Test
    void uploadImageToAllEnvironments_解決済みの長辺pxを渡す形ではメディア設定を引かずにその値でリサイズする() throws Exception {
        bind(ssh(10L, "local-site", "ssh-local"), null, null);

        List<BulkOperationLog> results = service.uploadImageToAllEnvironments(
                1L, png(1600, 100), "cat.png", "image/png", 800, 9L);

        assertEquals(1, results.size());
        assertEquals(BulkOperationStatus.SUCCESS, results.get(0).getStatus());
        assertTrue(world.media.requests().isEmpty(), "メディア設定は引かない");
        byte[] uploaded = world.ssh.site("ssh-local", "/var/www/local-site").media.values().iterator().next().bytes;
        assertEquals(800, sizeOf(uploaded)[0]);
    }

    @Test
    void uploadImageToAllEnvironments_画素数が上限を超える画像はどの環境へもアップロードせず失敗として返す() {
        bind(ssh(10L, "local-site", "ssh-local"), ssh(20L, "test-site", "ssh-test"), null);

        List<BulkOperationLog> results = service.uploadImageToAllEnvironments(
                1L, OversizedImageFixtures.pngHeaderOnly(30000, 30000), "bomb.png", "image/png", 9L);

        assertEquals(2, results.size());
        for (BulkOperationLog log : results) {
            assertEquals(BulkOperationStatus.FAILED, log.getStatus());
            assertTrue(log.getErrorMessage().contains("画素数"), log.getErrorMessage());
        }
        assertTrue(world.ssh.commands.isEmpty(), "CMSへは何も送らない");
        assertTrue(world.project.requests("GET", ".*/credentials").isEmpty(), "認証情報の解決にも進まない");
    }

    @Test
    void uploadImageToAllEnvironments_JPEG変換された場合はファイル名拡張子とContentTypeもjpgに揃える() throws Exception {
        bind(ssh(10L, "local-site", "ssh-local"), null, null);

        List<BulkOperationLog> results = service.uploadImageToAllEnvironments(
                1L, png(100, 100), "comfyui-5.png", "image/png", 9L);

        assertEquals(1, results.size());
        assertEquals(BulkOperationStatus.SUCCESS, results.get(0).getStatus());
        assertTrue(results.get(0).getValue().endsWith("-comfyui-5.jpg"), results.get(0).getValue());
        FakeWordPress.Media uploaded = world.ssh.site("ssh-local", "/var/www/local-site").media.values().iterator().next();
        assertEquals(100, sizeOf(uploaded.bytes)[0]);
        assertTrue(uploaded.bytes.length > 2 && (uploaded.bytes[0] & 0xFF) == 0xFF && (uploaded.bytes[1] & 0xFF) == 0xD8,
                "JPEGとしてアップロードされる");
    }

    @Test
    void uploadImageToAllEnvironments_画像でなければファイル名の拡張子を変えずそのままアップロードする() {
        bind(ssh(10L, "local-site", "ssh-local"), null, null);

        List<BulkOperationLog> results = service.uploadImageToAllEnvironments(
                1L, new byte[]{1, 2, 3}, "anim.gif", "image/gif", 9L);

        assertTrue(results.get(0).getValue().endsWith("-anim.gif"), results.get(0).getValue());
    }

    @Test
    void uploadImageToAllEnvironments_サイト未紐付けの環境はスキップされる() {
        bind(ssh(10L, "local-site", "ssh-local"), null, null);

        List<BulkOperationLog> results = service.uploadImageToAllEnvironments(
                1L, new byte[]{1}, "cat.png", "image/png", 9L);

        assertEquals(1, results.size());
        assertEquals("local", results.get(0).getEnvironment());
    }

    // ---- issue #1688: アセット画像の全環境アップロードの環境間並列 ----

    private void bindThreeSshSites() {
        bind(ssh(10L, "local-site", "ssh-local"), ssh(20L, "test-site", "ssh-test"),
                ssh(30L, "production-site", "ssh-production"));
    }

    @Test
    void uploadImageToAllEnvironments_環境ごとのアップロードが重なって走り_結果はENVIRONMENT_ORDER順_リサイズは1回() {
        bindThreeSshSites();
        OverlapProbe probe = new OverlapProbe(3);
        world.ssh.onCommand("media import", params -> {
            // localを最後に終わらせても、結果の並びは変わらない
            if (params.host().equals("ssh-local")) {
                sleep(150);
            }
            probe.enter();
        });

        List<BulkOperationLog> results = service.uploadImageToAllEnvironments(
                1L, new byte[]{1, 2, 3}, "cat.png", "image/png", 9L);

        assertTrue(probe.allOverlapped(), "3環境のアップロードが重なって呼ばれていない(逐次実行)");
        assertEquals(List.of("local", "test", "production"),
                results.stream().map(BulkOperationLog::getEnvironment).toList());
        assertTrue(results.get(0).getValue().endsWith("-cat.png"));
        assertEquals(1, world.media.requests("GET", ".*/article-image-long-edge-px").size(), "設定の照会は1回");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void uploadImageToAllEnvironments_先頭環境の失敗でも他環境は最後まで実行され_順序は保たれる() {
        bindThreeSshSites();
        world.ssh.rejectHost("ssh-local");
        // testは認証情報の解決自体が失敗する(並列タスク内に含まれる)
        world.project.status("GET", "/api/internal/project/sites/test-site/credentials", 500, "認証情報なし");
        world.ssh.onCommand("media import", params -> sleep(100));

        List<BulkOperationLog> results = service.uploadImageToAllEnvironments(
                1L, new byte[]{1, 2, 3}, "cat.png", "image/png", 9L);

        assertEquals(List.of("local", "test", "production"),
                results.stream().map(BulkOperationLog::getEnvironment).toList());
        assertEquals(List.of(BulkOperationStatus.FAILED, BulkOperationStatus.FAILED, BulkOperationStatus.SUCCESS),
                results.stream().map(BulkOperationLog::getStatus).toList());
        assertTrue(results.get(0).getErrorMessage().contains("ssh-local"));
        assertTrue(results.get(1).getErrorMessage().contains("認証情報"), results.get(1).getErrorMessage());
        assertEquals("cat.png", results.get(1).getValue());
        assertTrue(results.get(2).getValue().endsWith("-cat.png"));
    }

    @Test
    void uploadImageToAllEnvironments_紐付けられたサイトが登録されていない環境はスキップされる() {
        bind(null, ssh(20L, "test-site", "ssh-test"), null);
        world.bindProject(20L, 1L, 10L, 20L, null, "test");

        List<BulkOperationLog> results = service.uploadImageToAllEnvironments(
                1L, new byte[]{1, 2, 3}, "cat.png", "image/png", 9L);

        assertEquals(List.of("test"), results.stream().map(BulkOperationLog::getEnvironment).toList());
    }

    // ---- deletePostAtEnvironment / updatePostStatusAtEnvironment ----

    @Test
    void deletePostAtEnvironment_成功時はCmsAdapter経由で削除しSUCCESSを記録する() {
        bind(ssh(10L, "local-site", "ssh-local"), null, null);
        FakeWordPress.Site remote = world.ssh.site("ssh-local", "/var/www/local-site");
        remote.addPost("101", "post", "hello", "hello", "publish");

        BulkOperationLog result = service.deletePostAtEnvironment(
                1L, "local", siteOf(10L, "local-site"), "101", "post", "hello", 9L);

        assertEquals(BulkOperationStatus.SUCCESS, result.getStatus());
        assertEquals(BulkOperationType.POST_DELETE, result.getOperationType());
        assertEquals("hello", result.getValue());
        assertEquals("trash", remote.posts.get("101").status);
    }

    @Test
    void deletePostAtEnvironment_失敗時はFAILEDを記録し例外を投げない() {
        bind(ssh(10L, "local-site", "ssh-local"), null, null);
        // 投稿101はWordPressに存在しない。

        BulkOperationLog result = service.deletePostAtEnvironment(
                1L, "local", siteOf(10L, "local-site"), "101", "post", "hello", 9L);

        assertEquals(BulkOperationStatus.FAILED, result.getStatus());
        assertTrue(result.getErrorMessage().contains("101"), result.getErrorMessage());
    }

    @Test
    void updatePostStatusAtEnvironment_成功時はステータスをpost_statusへ記録する() {
        bind(ssh(10L, "local-site", "ssh-local"), null, null);
        FakeWordPress.Site remote = world.ssh.site("ssh-local", "/var/www/local-site");
        remote.addPost("101", "post", "hello", "hello", "draft");

        BulkOperationLog result = service.updatePostStatusAtEnvironment(
                1L, "local", siteOf(10L, "local-site"), "101", "post", "hello", "publish", 9L);

        assertEquals(BulkOperationStatus.SUCCESS, result.getStatus());
        assertEquals(BulkOperationType.POST_STATUS_UPDATE, result.getOperationType());
        assertEquals("publish", result.getPostStatus());
        assertEquals("publish", remote.posts.get("101").status);
    }

    @Test
    void updatePostStatusAtEnvironment_失敗時はFAILEDを記録し例外を投げない() {
        bind(ssh(10L, "local-site", "ssh-local"), null, null);

        BulkOperationLog result = service.updatePostStatusAtEnvironment(
                1L, "local", siteOf(10L, "local-site"), "101", "post", "hello", "publish", 9L);

        assertEquals(BulkOperationStatus.FAILED, result.getStatus());
        assertNotNull(result.getErrorMessage());
    }

    // ---- 非managedサイト: SSH経由での適用 ----

    @Test
    void applyToEnvironment_非managedサイトはSSH経由で適用する() {
        bind(ssh(10L, "external-site", EXT_HOST), null, null);
        FakeWordPress.Site remote = world.ssh.site(EXT_HOST, "/var/www/external-site");
        remote.plugins.put("akismet", "inactive");

        BulkOperationLog result = service.applyToEnvironment(
                1L, "local", BulkOperationType.PLUGIN_ACTIVATE, "akismet", null, null, null, null, 9L);

        assertEquals(BulkOperationStatus.SUCCESS, result.getStatus());
        assertEquals("active", remote.plugins.get("akismet"));
        assertTrue(applied().isEmpty(), "provision-agentは使わない");
    }

    @Test
    void applyToEnvironment_非managedサイトのカテゴリ削除もSSH経由で適用する() {
        bind(ssh(10L, "external-site", EXT_HOST), null, null);
        FakeWordPress.Site remote = world.ssh.site(EXT_HOST, "/var/www/external-site");
        remote.addTerm("category", "5", "お知らせ", "oshirase", null, null);

        BulkOperationLog result = service.applyToEnvironment(
                1L, "local", BulkOperationType.CATEGORY_DELETE, null, null, null, null, "oshirase", 9L);

        assertEquals(BulkOperationStatus.SUCCESS, result.getStatus());
        assertTrue(remote.termsById.isEmpty(), "カテゴリが削除される");
    }

    @Test
    void applyToEnvironment_非managedサイトのカテゴリ作成_編集もSSH経由で適用する() {
        bind(ssh(10L, "external-site", EXT_HOST), null, null);
        FakeWordPress.Site remote = world.ssh.site(EXT_HOST, "/var/www/external-site");

        BulkOperationLog created = service.applyToEnvironment(
                1L, "local", BulkOperationType.TAG_CREATE, "新着", "shinchaku", null, null, null, 9L);
        BulkOperationLog edited = service.applyToEnvironment(
                1L, "local", BulkOperationType.TAG_EDIT, "最新", "saishin", null, null, "shinchaku", 9L);

        assertEquals(BulkOperationStatus.SUCCESS, created.getStatus());
        assertEquals(BulkOperationStatus.SUCCESS, edited.getStatus());
        assertEquals(1, remote.termsById.size());
        assertEquals("saishin", remote.termsById.values().iterator().next().slug);
    }

    @Test
    void applyToEnvironment_SSHが失敗したらFAILEDを記録する() {
        bind(ssh(10L, "external-site", EXT_HOST), null, null);
        // テーマtwentytwentyfourはインストールされていない。

        BulkOperationLog result = service.applyToEnvironment(
                1L, "local", BulkOperationType.THEME_ACTIVATE, "twentytwentyfour", null, null, null, null, 9L);

        assertEquals(BulkOperationStatus.FAILED, result.getStatus());
        assertTrue(result.getErrorMessage().contains("テーマの有効化に失敗しました"), result.getErrorMessage());
    }

    @Test
    void logFetchFailure_例外を投げずに完了する() {
        assertDoesNotThrow(() -> service.logFetchFailure(1L, BulkOperationType.CATEGORY_FETCH, "test",
                "Connection refused", "java.io.IOException: Connection refused\n\tat ..."));
    }

    // ---- issue #1687: 環境間の並列実行 ----

    @Test
    void applyToAllEnvironments_環境ごとのインストールが重なって走り_結果はENVIRONMENT_ORDER順() {
        bindThreeManagedSites();
        OverlapProbe probe = new OverlapProbe(3);
        world.agent.on("POST", "/bulk-management", request -> {
            // localを最後に終わらせても、結果の並びは変わらない
            if (request.bodyText().contains("local-site")) {
                sleep(150);
            }
            probe.enter();
            return Response.ok("{\"status\":\"success\"}");
        });

        List<BulkOperationLog> results = service.applyToAllEnvironments(
                1L, BulkOperationType.PLUGIN_INSTALL, "akismet", 9L);

        assertTrue(probe.allOverlapped(), "3環境のインストールが重なって呼ばれていない(逐次実行)");
        assertEquals(List.of("local", "test", "production"),
                results.stream().map(BulkOperationLog::getEnvironment).toList());
    }

    @Test
    void applyToAllEnvironments_1環境の実行時例外でも他環境は最後まで実行され_例外は呼び出し元へ伝わる() {
        bind(managed(10L, "local-site"), managed(20L, "test-site"), managed(30L, "production-site"));
        // サイト10は紐付いているのにproject-serviceに登録されていない(SiteNotFoundException)。
        world.project.status("GET", "/api/internal/project/sites/10", 404, "{}");
        world.agent.on("POST", "/bulk-management", request -> {
            sleep(100);
            return Response.ok("{\"status\":\"success\"}");
        });

        SiteNotFoundException thrown = assertThrows(SiteNotFoundException.class, () -> service.applyToAllEnvironments(
                1L, BulkOperationType.PLUGIN_INSTALL, "akismet", 9L));

        assertTrue(thrown.getMessage().contains("10"), thrown.getMessage());
        assertTrue(wasApplied("test-site", "plugin_install", "akismet"));
        assertTrue(wasApplied("production-site", "plugin_install", "akismet"));
    }

    @Test
    void applyToAllEnvironments_対象外環境のFAILEDは並列でも環境順の位置に記録される() {
        bind(managed(10L, "local-site"), external(20L, "external-site"), managed(30L, "production-site"));

        List<BulkOperationLog> results = service.applyToAllEnvironments(
                1L, BulkOperationType.PLUGIN_INSTALL, "akismet", 9L);

        assertEquals(List.of("local", "test", "production"),
                results.stream().map(BulkOperationLog::getEnvironment).toList());
        assertEquals(List.of(BulkOperationStatus.SUCCESS, BulkOperationStatus.FAILED, BulkOperationStatus.SUCCESS),
                results.stream().map(BulkOperationLog::getStatus).toList());
    }

    @Test
    void executeFromUpload_環境ごとの転送が重なって走り_結果はENVIRONMENT_ORDER順() throws IOException {
        bindThreeManagedSites();
        MockMultipartFile file = new MockMultipartFile("file", "p.zip", "application/zip", new byte[]{1, 2});
        OverlapProbe probe = new OverlapProbe(3);
        world.agent.on("POST", "/bulk-management/upload", request -> {
            if (request.bodyText().contains("local-site")) {
                sleep(150);
            }
            probe.enter();
            return Response.ok("{\"status\":\"success\"}");
        });

        List<BulkOperationLog> results = service.executeFromUpload(1L, BulkOperationType.PLUGIN_INSTALL, file, 9L);

        assertTrue(probe.allOverlapped(), "3環境のzip転送が重なって呼ばれていない(逐次実行)");
        assertEquals(List.of("local", "test", "production"),
                results.stream().map(BulkOperationLog::getEnvironment).toList());
    }

    @Test
    void executeFromUpload_1環境の実行時例外でも他環境は最後まで実行され_例外は呼び出し元へ伝わる() throws IOException {
        bind(ssh(10L, "local-site", "ssh-local"), ssh(20L, "test-site", "ssh-test"),
                ssh(30L, "production-site", "ssh-production"));
        MockMultipartFile file = new MockMultipartFile("file", "p.zip", "application/zip", new byte[]{1, 2});
        // SFTPクライアントが想定外の実行時例外を投げる(SshOperationExceptionに包まれない失敗)。
        world.ssh.failPutFile(new IllegalStateException("zip failed"));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service.executeFromUpload(1L, BulkOperationType.PLUGIN_INSTALL, file, 9L));

        assertEquals("zip failed", thrown.getMessage());
    }

    // ---- issue #1702: ジョブ(非同期)から呼ぶための分離 ----

    @Test
    void validateSlugInstall_プラグインとテーマのインストールでslugがあれば通る() {
        assertDoesNotThrow(() -> service.validateSlugInstall(BulkOperationType.PLUGIN_INSTALL, "akismet"));
        assertDoesNotThrow(() -> service.validateSlugInstall(BulkOperationType.THEME_INSTALL, "twentytwentyfive"));
    }

    @Test
    void validateSlugInstall_インストール以外の操作は例外() {
        assertThrows(IllegalArgumentException.class,
                () -> service.validateSlugInstall(BulkOperationType.PLUGIN_ACTIVATE, "akismet"));
    }

    @Test
    void validateSlugInstall_slugが空白またはnullなら例外() {
        assertThrows(IllegalArgumentException.class,
                () -> service.validateSlugInstall(BulkOperationType.PLUGIN_INSTALL, " "));
        assertThrows(IllegalArgumentException.class,
                () -> service.validateSlugInstall(BulkOperationType.PLUGIN_INSTALL, null));
    }

    @Test
    void validateZipInstall_zipなら通る() {
        MockMultipartFile file = new MockMultipartFile("file", "p.zip", "application/zip", new byte[]{1});

        assertDoesNotThrow(() -> service.validateZipInstall(BulkOperationType.PLUGIN_INSTALL, file));
    }

    @Test
    void validateZipInstall_インストール以外_拡張子違い_空ファイルは例外() {
        MockMultipartFile ok = new MockMultipartFile("file", "p.zip", "application/zip", new byte[]{1});
        MockMultipartFile txt = new MockMultipartFile("file", "p.txt", "text/plain", new byte[]{1});
        MockMultipartFile empty = new MockMultipartFile("file", "p.zip", "application/zip", new byte[0]);
        MockMultipartFile noName = new MockMultipartFile("file", null, "application/zip", new byte[]{1});
        MockMultipartFile wrongType = new MockMultipartFile("file", "p.zip", "text/plain", new byte[]{1});
        MockMultipartFile octet = new MockMultipartFile("file", "p.zip", "application/octet-stream", new byte[]{1});

        assertThrows(IllegalArgumentException.class,
                () -> service.validateZipInstall(BulkOperationType.CATEGORY_CREATE, ok));
        assertThrows(IllegalArgumentException.class,
                () -> service.validateZipInstall(BulkOperationType.PLUGIN_INSTALL, txt));
        assertThrows(IllegalArgumentException.class,
                () -> service.validateZipInstall(BulkOperationType.PLUGIN_INSTALL, empty));
        assertThrows(IllegalArgumentException.class,
                () -> service.validateZipInstall(BulkOperationType.PLUGIN_INSTALL, null));
        assertThrows(IllegalArgumentException.class,
                () -> service.validateZipInstall(BulkOperationType.PLUGIN_INSTALL, noName));
        assertThrows(IllegalArgumentException.class,
                () -> service.validateZipInstall(BulkOperationType.PLUGIN_INSTALL, wrongType));
        assertDoesNotThrow(() -> service.validateZipInstall(BulkOperationType.PLUGIN_INSTALL, octet));
    }

    @Test
    void applyStoredZipToAllEnvironments_保存済みのバイト列を再保存せず各環境へ転送しログに保存先を載せる() throws IOException {
        bind(managed(10L, "local-site"), managed(20L, "test-site"), null);
        byte[] content = new byte[]{1, 2, 3};
        BulkUploadStorageService.StoredZip stored =
                new BulkUploadStorageService.StoredZip("1/abc.zip", "abc", "custom-theme.zip");

        List<BulkOperationLog> results =
                service.applyStoredZipToAllEnvironments(1L, BulkOperationType.THEME_INSTALL, content, stored, 9L);

        assertEquals(List.of("local", "test"), results.stream().map(BulkOperationLog::getEnvironment).toList());
        assertEquals("1/abc.zip", results.get(0).getStoragePath());
        assertEquals("abc", results.get(0).getFileSha256());
        assertEquals(BulkOperationSourceType.ZIP, results.get(0).getSourceType());
        assertEquals(9L, results.get(0).getActorId());
        assertEquals(2, zipUploads().size());
        assertThrows(IOException.class, () -> world.bulkUploadStorageService.load("1/abc.zip"), "再保存しない");
    }

    // ---- 境界の補完(issue #1815) ----

    @Test
    void uploadImageToAllEnvironments_拡張子の無いファイル名でもJPEG変換後は拡張子jpgを付ける() throws Exception {
        bind(ssh(10L, "local-site", "ssh-local"), null, null);

        List<BulkOperationLog> results = service.uploadImageToAllEnvironments(
                1L, png(100, 100), "cat", "image/png", 9L);

        assertTrue(results.get(0).getValue().endsWith("-cat.jpg"), results.get(0).getValue());
    }

    @Test
    void applyToEnvironment_categoryDelete_valueが空白でもtargetSlugで補われる() {
        bind(managed(10L, "local-site"), null, null);

        BulkOperationLog result = service.applyToEnvironment(
                1L, "local", BulkOperationType.CATEGORY_DELETE, "  ", null, null, null, "oshirase", 9L);

        assertEquals("oshirase", result.getValue());
    }

    @Test
    void applyToEnvironment_SSH接続情報が適用の直前までに無くなっていたら例外() {
        bind(ssh(10L, "external-site", EXT_HOST), null, null);
        // 1回目(環境の検証)ではSSH接続情報があり、2回目(適用の直前)にはtransportがSSHでなくなっている。
        Map<String, String> agent = new java.util.HashMap<>(PublishingWorld.sshCredentialsMap());
        agent.put("transport", "AGENT");
        world.project.sequence("GET", "/api/internal/project/sites/external-site/credentials",
                Response.ok(world.json(Map.of("siteId", 10, "cmsType", "WORDPRESS",
                        "credentials", sshCredentialsFor(EXT_HOST, "/var/www/external-site")))),
                Response.ok(world.json(Map.of("siteId", 10, "cmsType", "WORDPRESS", "credentials", agent))));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> service.applyToEnvironment(
                1L, "local", BulkOperationType.PLUGIN_ACTIVATE, "akismet", null, null, null, null, 9L));

        assertTrue(e.getMessage().contains("SSH接続が設定されていない"), e.getMessage());
    }

    private static Map<String, String> sshCredentialsFor(String host, String wpPath) {
        Map<String, String> map = new java.util.HashMap<>(PublishingWorld.sshCredentialsMap());
        map.put("sshHost", host);
        map.put("wpPath", wpPath);
        return map;
    }

    @Test
    void validateZipInstall_ファイル名もContentTypeも無いzipは_名前が無い場合だけ例外() {
        com.letsblog.publishing.service.BytesMultipartFile noName =
                new com.letsblog.publishing.service.BytesMultipartFile("file", null, "application/zip", new byte[]{1});
        com.letsblog.publishing.service.BytesMultipartFile noType =
                new com.letsblog.publishing.service.BytesMultipartFile("file", "p.zip", null, new byte[]{1});

        assertThrows(IllegalArgumentException.class,
                () -> service.validateZipInstall(BulkOperationType.PLUGIN_INSTALL, noName));
        assertDoesNotThrow(() -> service.validateZipInstall(BulkOperationType.PLUGIN_INSTALL, noType));
    }
}
