package com.letsblog.publishing.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.letsblog.common.messaging.PostPublishedEvent;
import com.letsblog.publishing.cms.LetsblogPluginUnavailableException;
import com.letsblog.publishing.dto.PostPublishCommand;
import com.letsblog.publishing.dto.PostPublishResponse;
import com.letsblog.publishing.render.PlantUmlRenderRejectedException;
import com.letsblog.publishing.testsupport.FakeHttpServer;
import com.letsblog.publishing.testsupport.FakeHttpServer.Response;
import com.letsblog.publishing.testsupport.FakeWordPress;
import com.letsblog.publishing.testsupport.PublishingWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * PostPublishServiceの回帰テスト(legacy-apiから移設。issue #707)。画像ファイル名リネーム
 * ({slug}-{4桁連番}.{拡張子})とfeatured_image指定時のfeatured_media解決を中心に検証する。
 *
 * <p>issue #1815で書き直し: 自前のクラス(ProjectServiceClient・ContentServiceClient・IdentityBridgeClient・
 * MediaSettingsBridgeClient・CurrentActorService・AdminAuthorizationService・CmsAdapterFactory・
 * WordPressAdapter・PlantUml*Service・ImageResizeService・DomainEventPublisher)は実物を使い、
 * 外部との通信だけを差し替える。HTTP(project/identity/content/media-service)は{@link FakeHttpServer}、
 * WordPressへのSSH(wp-cli)は{@link FakeWordPress}、MQは{@code RabbitTemplate}のMockito。
 * 投稿の内容(タイトル・本文・ステータス・アイキャッチ・著者・予約日時)は、WordPressのフェイクに
 * 実際に書き込まれた状態で確かめる。
 */
class PostPublishServiceTest {

    private static final String ADMIN = "Bearer admin-token";

    private PublishingWorld world;
    private PostPublishService service;

    @BeforeEach
    void setUp() {
        world = new PublishingWorld().withMainSite();
        world.stubActor(ADMIN, 1L, "admin", "admin@example.com");
        world.asUser(ADMIN);
        service = world.postPublishService;
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    private PostPublishCommand command(String slug, String title, List<MultipartFile> images, String featuredImageFilename) {
        return command(slug, title, images, featuredImageFilename, null);
    }

    private PostPublishCommand command(String slug, String title, List<MultipartFile> images,
            String featuredImageFilename, List<String> imageReferences) {
        return new PostPublishCommand(
                "main", title, slug, "draft", List.of(), List.of(), null, "本文", images, featuredImageFilename,
                imageReferences, null);
    }

    // ---- 観測用の部品 ----

    private JsonNode lastJson(FakeHttpServer server, String method, String path) {
        List<FakeHttpServer.Request> requests = server.requests(method, path);
        assertFalse(requests.isEmpty(), method + " " + path + " が呼ばれていません: " + server.paths());
        try {
            return world.mapper.readTree(requests.get(requests.size() - 1).body());
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode upsert() {
        return lastJson(world.content, "PUT", "/api/internal/content/posts");
    }

    private JsonNode preImageRequest() {
        return lastJson(world.content, "POST", "/api/internal/content/render/pre-image");
    }

    private String finalizedMarkdown() {
        return lastJson(world.content, "POST", "/api/internal/content/render/finalize-html")
                .path("markdown").asText();
    }

    private void preImageReturns(String markdown) {
        world.content.json("POST", "/api/internal/content/render/pre-image", world.json(Map.of("markdown", markdown)));
    }

    private FakeWordPress.Media uploaded(String filename) {
        return world.ssh.media.values().stream().filter(m -> m.url.endsWith("-" + filename)).findFirst()
                .orElseThrow(() -> new AssertionError(filename + " はアップロードされていません: "
                        + world.ssh.media.values().stream().map(m -> m.url).toList()));
    }

    private boolean wasUploaded(String filename) {
        return world.ssh.media.values().stream().anyMatch(m -> m.url.endsWith("-" + filename));
    }

    private FakeWordPress.Post created(PostPublishResponse response) {
        return world.ssh.posts.get(response.wpPostId());
    }

    private PostPublishedEvent publishedEvent() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(world.rabbitTemplate).convertAndSend(eq("letsblog.events"), eq("post.published"), captor.capture());
        return (PostPublishedEvent) captor.getValue();
    }

    private static String uploadedImagesJson(String reference, String sha256, String url, String mediaId) {
        return "{\"" + reference + "\":{\"sha256\":\"" + sha256 + "\",\"url\":\"" + url + "\",\"mediaId\":\""
                + mediaId + "\"}}";
    }

    @Test
    void publish_画素数が上限を超える添付画像は断り_WordPressへ何も送らない() {
        List<MultipartFile> images = List.of(new MockMultipartFile(
                "images", "bomb.png", "image/png", OversizedImageFixtures.pngHeaderOnly(30000, 30000)));

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.publish(command("my-article", "My Article", images, null)));

        assertTrue(e.getMessage().contains("画素数"), e.getMessage());
        assertTrue(world.ssh.media.isEmpty(), "メディアはアップロードされない");
        assertTrue(world.ssh.posts.isEmpty(), "投稿は作成されない");
    }

    @Test
    void publish_画像が複数ある場合はslug連番でリネームされる() {
        List<MultipartFile> images = List.of(
                new MockMultipartFile("images", "eyecatch.png", "image/png", new byte[]{1}),
                new MockMultipartFile("images", "photo.jpg", "image/jpeg", new byte[]{2}));

        PostPublishResponse response = service.publish(command("my-article", "My Article", images, null));

        assertNotEquals(null, response.wpPostId());
        assertTrue(world.ssh.posts.containsKey(response.wpPostId()));
        assertTrue(wasUploaded("my-article-0001.png"));
        assertTrue(wasUploaded("my-article-0002.jpg"));
        assertEquals(2, world.ssh.media.size());
    }

    @Test
    void publish_既存スラッグのwpPostIdを渡して再投稿するとcontent_serviceへ同じwpPostIdで反映を依頼する() {
        world.stubContentPost(1L, "55", "my-article", null);
        world.ssh.addPost("55", "my-article", "draft");

        PostPublishCommand command = new PostPublishCommand(
                "main", "My Article", "my-article", "draft", List.of(), List.of(), "55", "本文", List.of(), null,
                null, null);

        service.publish(command);

        JsonNode body = upsert();
        assertEquals(1L, body.path("siteId").asLong());
        assertEquals("55", body.path("wpPostId").asText());
        assertEquals("my-article", body.path("slug").asText());
        assertEquals("draft", body.path("status").asText());
        PostPublishedEvent event = publishedEvent();
        assertEquals(1L, event.siteId());
        assertEquals("55", event.wpPostId());
        assertEquals("https://example.com/?p=55", event.url());
        assertEquals("draft", event.status());
    }

    @Test
    void publish_slug未指定時はtitleから簡易スラッグ化される() {
        List<MultipartFile> images = List.of(
                new MockMultipartFile("images", "eyecatch.png", "image/png", new byte[]{1}));

        service.publish(command(null, "Hello, World! Blog", images, null));

        assertTrue(wasUploaded("hello-world-blog-0001.png"));
    }

    @Test
    void publish_titleが特殊文字のみの場合はpostがデフォルトになる() {
        List<MultipartFile> images = List.of(
                new MockMultipartFile("images", "eyecatch.png", "image/png", new byte[]{1}));

        service.publish(command(null, "!!!###", images, null));

        assertTrue(wasUploaded("post-0001.png"));
    }

    @Test
    void publish_元ファイル名の拡張子が保持される() {
        List<MultipartFile> images = List.of(
                new MockMultipartFile("images", "anim.gif", "image/gif", new byte[]{1}));

        service.publish(command("my-article", "My Article", images, null));

        assertTrue(wasUploaded("my-article-0001.gif"));
    }

    @Test
    void publish_最終HTMLはcontent_serviceのfinalizeHtml結果がPostContentへ渡される() {
        world.content.json("POST", "/api/internal/content/render/finalize-html",
                world.json(Map.of("html", "<div class=\"lets-blog-rendered\">wrapped</div>")));

        PostPublishResponse response = service.publish(command("my-article", "My Article", List.of(), null));

        assertEquals("本文", finalizedMarkdown());
        assertTrue(lastJson(world.content, "POST", "/api/internal/content/render/finalize-html")
                .path("projectId").isNull());
        assertEquals("<div class=\"lets-blog-rendered\">wrapped</div>", world.ssh.contentOf(response.wpPostId()));
    }

    @Test
    void publish_featuredImageFilenameに一致する画像のfeaturedMediaIdがPostContentへ設定される() {
        List<MultipartFile> images = List.of(
                new MockMultipartFile("images", "eyecatch.png", "image/png", new byte[]{1}),
                new MockMultipartFile("images", "photo.jpg", "image/jpeg", new byte[]{2}));

        PostPublishResponse response = service.publish(command("my-article", "My Article", images, "eyecatch.png"));

        assertEquals(uploaded("my-article-0001.png").id, created(response).thumbnailId);
    }

    @Test
    void publish_アイキャッチなし投稿ではfeaturedMediaIdがnullになる() {
        PostPublishResponse response = service.publish(command("my-article", "My Article", List.of(), null));

        assertNull(created(response).thumbnailId);
        assertFalse(world.ssh.ran("_thumbnail_id"));
    }

    @Test
    void publish_Markdown中の元ファイル名参照がアップロード後のURLに置換される() {
        preImageReturns("![alt](eyecatch.png)");
        List<MultipartFile> images = List.of(
                new MockMultipartFile("images", "eyecatch.png", "image/png", new byte[]{1}));

        service.publish(command("my-article", "My Article", images, null));

        assertEquals("![alt](" + uploaded("my-article-0001.png").url + ")", finalizedMarkdown());
    }

    @Test
    void publish_assetsサブフォルダ参照はimageReferencesを使い二重結合されずに置換される() {
        preImageReturns("![alt](assets/eyecatch.png)");
        List<MultipartFile> images = List.of(
                new MockMultipartFile("images", "eyecatch.png", "image/png", new byte[]{1}));

        PostPublishResponse response = service.publish(command("my-article", "My Article", images,
                "assets/eyecatch.png", List.of("assets/eyecatch.png")));

        FakeWordPress.Media media = uploaded("my-article-0001.png");
        assertEquals("![alt](" + media.url + ")", finalizedMarkdown());
        assertEquals(media.id, created(response).thumbnailId);
    }

    // issue #1060: 一方の参照がもう一方の部分文字列になっている場合(eyecatch.png ⊂ assets/eyecatch.png、
    // img1.png ⊂ img10.png)に、単純なString#replaceの全件置換だと短い方が長い方の内部を書き換えてしまい、
    // 長い方の置換が黙って失敗する回帰を防ぐ。

    @Test
    void publish_短い参照が長い参照の部分文字列でもそれぞれ正しいURLに置換される() {
        preImageReturns("![a](eyecatch.png)\n![b](assets/eyecatch.png)");
        List<MultipartFile> images = List.of(
                new MockMultipartFile("images", "eyecatch.png", "image/png", new byte[]{1}),
                new MockMultipartFile("images", "eyecatch2.png", "image/png", new byte[]{2}));

        service.publish(command("my-article", "My Article", images, null,
                List.of("eyecatch.png", "assets/eyecatch.png")));

        assertEquals("![a](" + uploaded("my-article-0001.png").url + ")\n![b]("
                + uploaded("my-article-0002.png").url + ")", finalizedMarkdown());
    }

    @Test
    void publish_imagesの並び順を入れ替えても置換結果は同一になる() {
        preImageReturns("![a](eyecatch.png)\n![b](assets/eyecatch.png)");
        // 1番目(0001)がassets/eyecatch.png、2番目(0002)がeyecatch.pngになる。それでも各参照の置換先URLは、
        // その参照に対応する画像のアップロード結果でなければならない。
        List<MultipartFile> images = List.of(
                new MockMultipartFile("images", "eyecatch2.png", "image/png", new byte[]{2}),
                new MockMultipartFile("images", "eyecatch.png", "image/png", new byte[]{1}));

        service.publish(command("my-article", "My Article", images, null,
                List.of("assets/eyecatch.png", "eyecatch.png")));

        assertEquals("![a](" + uploaded("my-article-0002.png").url + ")\n![b]("
                + uploaded("my-article-0001.png").url + ")", finalizedMarkdown());
    }

    @Test
    void publish_数字接尾辞違いの参照でも短い方が長い方の内部を書き換えない() {
        preImageReturns("![a](img1.png)\n![b](img10.png)");
        List<MultipartFile> images = List.of(
                new MockMultipartFile("images", "img1.png", "image/png", new byte[]{1}),
                new MockMultipartFile("images", "img10.png", "image/png", new byte[]{2}));

        service.publish(command("my-article", "My Article", images, null, List.of("img1.png", "img10.png")));

        assertEquals("![a](" + uploaded("my-article-0001.png").url + ")\n![b]("
                + uploaded("my-article-0002.png").url + ")", finalizedMarkdown());
    }

    @Test
    void publish_本文中に見つからない参照がある場合は警告ログを出す() {
        preImageReturns("![a](other.png)");
        List<MultipartFile> images = List.of(
                new MockMultipartFile("images", "eyecatch.png", "image/png", new byte[]{1}));

        Logger logger = (Logger) LoggerFactory.getLogger(PostPublishService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            service.publish(command("my-article", "My Article", images, null, List.of("eyecatch.png")));
        } finally {
            logger.detachAppender(appender);
        }

        boolean warned = appender.list.stream()
                .anyMatch(event -> event.getLevel() == ch.qos.logback.classic.Level.WARN
                        && event.getFormattedMessage().contains("eyecatch.png"));
        assertTrue(warned, "本文中に見つからなかった参照についてWARNレベルのログが出力されること");
        assertEquals("![a](other.png)", finalizedMarkdown());
    }

    // 以下は#1060の変更でこのファイル全体のC1/C2カバレッジがゲート(90%)を割ったために追加した、
    // 既存分岐(このIssueの変更対象ではない箇所を含む)のカバレッジ補完。

    @Test
    void publish_statusが未指定の場合はdraftとして投稿される() {
        PostPublishCommand command = new PostPublishCommand(
                "main", "My Article", "my-article", null, List.of(), List.of(), null, "本文", List.of(), null,
                List.of(), null);

        PostPublishResponse response = service.publish(command);

        assertEquals("draft", created(response).status);
    }

    @Test
    void publish_投稿済み画像情報が空文字の場合はパースせず再アップロードして続行する() {
        world.stubContentPost(1L, "55", "my-article", "  ");
        world.ssh.addPost("55", "my-article", "draft");

        PostPublishCommand command = new PostPublishCommand(
                "main", "My Article", "my-article", "draft", List.of(), List.of(), "55", "本文", List.of(), null,
                List.of(), null);

        PostPublishResponse response = service.publish(command);

        assertEquals("55", response.wpPostId());
    }

    @Test
    void publish_投稿済み画像情報がJSONとして壊れていても再アップロードして続行する() {
        world.stubContentPost(1L, "55", "my-article", "{壊れたJSON");
        world.ssh.addPost("55", "my-article", "draft");
        List<MultipartFile> images = List.of(
                new MockMultipartFile("images", "eyecatch.png", "image/png", new byte[]{1}));

        PostPublishResponse response = service.publish(new PostPublishCommand(
                "main", "My Article", "my-article", "draft", List.of(), List.of(), "55", "本文", images, null,
                List.of("assets/eyecatch.png"), null));

        assertEquals("55", response.wpPostId());
        assertTrue(wasUploaded("my-article-0001.png"), "前回の情報を読めないので再アップロードする");
    }

    @Test
    void publish_発信者は判明しているがメールアドレス未設定なら著者未設定のまま続行する() {
        world.stubActor(ADMIN, 10L, "admin", null);

        PostPublishResponse response = service.publish(command("my-article", "My Article", List.of(), null));

        assertNull(created(response).author);
        assertFalse(world.ssh.ran("user list"), "メールが無いのでWordPressのユーザー検索は行わない");
    }

    @Test
    void publish_imagesがnullの場合は空扱いで画像処理をスキップする() {
        PostPublishCommand command = new PostPublishCommand(
                "main", "My Article", "my-article", "draft", List.of(), List.of(), null, "本文", null, null,
                List.of(), null);

        PostPublishResponse response = service.publish(command);

        assertTrue(world.ssh.posts.containsKey(response.wpPostId()));
        assertTrue(world.ssh.media.isEmpty());
    }

    @Test
    void publish_imagesが空でfeaturedImageFilenameのみ指定された場合は警告して続行する() {
        PostPublishResponse response = service.publish(command("my-article", "My Article", List.of(), "eyecatch.png"));

        assertNull(created(response).thumbnailId);
    }

    @Test
    void publish_imageReferencesがimagesより短い場合は不足分をoriginalFilenameで補う() {
        preImageReturns("![a](assets/eyecatch.png)\n![b](photo.png)");
        List<MultipartFile> images = List.of(
                new MockMultipartFile("images", "eyecatch.png", "image/png", new byte[]{1}),
                new MockMultipartFile("images", "photo.png", "image/png", new byte[]{2}));

        service.publish(command("my-article", "My Article", images, null, List.of("assets/eyecatch.png")));

        assertTrue(wasUploaded("my-article-0001.png"));
        assertTrue(wasUploaded("my-article-0002.png"));
        assertEquals("![a](" + uploaded("my-article-0001.png").url + ")\n![b](" + uploaded("my-article-0002.png").url
                + ")", finalizedMarkdown());
    }

    @Test
    void publish_参照文字列が空文字の画像はアップロード対象から除外される() {
        List<MultipartFile> images = List.of(
                new MockMultipartFile("images", "eyecatch.png", "image/png", new byte[]{1}));

        service.publish(command("my-article", "My Article", images, null, List.of("  ")));

        assertTrue(world.ssh.media.isEmpty());
    }

    @Test
    void publish_originalFilenameも参照も無い画像はアップロード対象から除外される() {
        List<MultipartFile> images = List.of(
                new MockMultipartFile("images", null, "image/png", new byte[]{1}));

        service.publish(command("my-article", "My Article", images, null, null));

        assertTrue(world.ssh.media.isEmpty());
    }

    @Test
    void publish_slugもtitleも未指定の場合はpostがデフォルトになる() {
        List<MultipartFile> images = List.of(
                new MockMultipartFile("images", "eyecatch.png", "image/png", new byte[]{1}));

        service.publish(command(null, null, images, null));

        assertTrue(wasUploaded("post-0001.png"));
    }

    @Test
    void publish_発信者に対応するWordPressユーザーが見つかればauthorIdを設定する() {
        world.stubActor(ADMIN, 10L, "admin", "author@example.com");
        world.ssh.users.put("author@example.com", "7");

        PostPublishResponse response = service.publish(command("my-article", "My Article", List.of(), null));

        assertEquals("7", created(response).author);
    }

    @Test
    void publish_user_site_authorsに対応表があればメール検索せずそれを使う() {
        world.stubActor(ADMIN, 10L, "admin", "author@example.com");
        world.identity.json("GET", "/api/internal/identity/user-site-authors/10/1", "{\"cmsAuthorId\":\"7\"}");

        PostPublishResponse response = service.publish(command("my-article", "My Article", List.of(), null));

        assertEquals("7", created(response).author);
        assertFalse(world.ssh.ran("user list"), "対応表があればメール検索しない");
    }

    @Test
    void publish_メール検索で解決できた場合はuser_site_authorsへキャッシュする() {
        world.stubActor(ADMIN, 10L, "admin", "author@example.com");
        world.ssh.users.put("author@example.com", "7");

        service.publish(command("my-article", "My Article", List.of(), null));

        JsonNode cached = lastJson(world.identity, "POST", "/api/internal/identity/user-site-authors");
        assertEquals(10L, cached.path("userId").asLong());
        assertEquals(1L, cached.path("siteId").asLong());
        assertEquals("7", cached.path("cmsAuthorId").asText());
    }

    @Test
    void publish_メール検索でも見つからなければキャッシュせず著者未設定のまま投稿する() {
        world.stubActor(ADMIN, 10L, "admin", "nobody@example.com");

        PostPublishResponse response = service.publish(command("my-article", "My Article", List.of(), null));

        assertNull(created(response).author);
        assertTrue(world.identity.requests("POST", "/api/internal/identity/user-site-authors").isEmpty());
    }

    @Test
    void publish_発信者不明時はauthorIdがnullのまま投稿を続行する() {
        // adminだがidを解決できないプロフィール(identity-serviceがidを返さない)。
        world.stubActor(ADMIN, null, "admin", null);

        PostPublishResponse response = service.publish(command("my-article", "My Article", List.of(), null));

        assertNull(created(response).author);
        assertTrue(world.identity.requests("GET", "/api/internal/identity/user-site-authors/.*").isEmpty());
    }

    @Test
    void publish_著者解決が例外を投げても投稿は続行される() {
        world.stubActor(ADMIN, 10L, "admin", "author@example.com");
        world.identity.status("GET", "/api/internal/identity/user-site-authors/10/1", 500, "{\"error\":\"down\"}");

        PostPublishResponse response = service.publish(command("my-article", "My Article", List.of(), null));

        assertTrue(world.ssh.posts.containsKey(response.wpPostId()));
        assertNull(created(response).author);
    }

    @Test
    void publish_長編が閾値を超える画像はリサイズしてからアップロードする() throws Exception {
        world.media.json("GET", "/api/internal/media/projects/\\d+/article-image-long-edge-px", "{\"value\":100}");
        world.bindProject(1L, 7L, null, null, 1L);
        List<MultipartFile> images = List.of(
                new MockMultipartFile("images", "eyecatch.png", "image/png", renderPng(400, 200)));
        PostPublishCommand command = new PostPublishCommand(
                "main", "My Article", "my-article", "draft", List.of(), List.of(), null, "本文", images, null,
                List.of("assets/eyecatch.png"), null);

        service.publish(command);

        BufferedImage uploaded = ImageIO.read(new ByteArrayInputStream(uploaded("my-article-0001.jpg").bytes));
        assertEquals(100, uploaded.getWidth());
        assertEquals(50, uploaded.getHeight());
    }

    @Test
    void publish_長編が閾値以下の画像はリサイズしない() throws Exception {
        world.bindProject(1L, 7L, null, null, 1L);
        List<MultipartFile> images = List.of(
                new MockMultipartFile("images", "eyecatch.png", "image/png", renderPng(400, 200)));
        PostPublishCommand command = new PostPublishCommand(
                "main", "My Article", "my-article", "draft", List.of(), List.of(), null, "本文", images, null,
                List.of("assets/eyecatch.png"), null);

        service.publish(command);

        BufferedImage uploaded = ImageIO.read(new ByteArrayInputStream(uploaded("my-article-0001.jpg").bytes));
        assertEquals(400, uploaded.getWidth());
        assertEquals(200, uploaded.getHeight());
    }

    @Test
    void publish_media_serviceの設定照会が空応答ならアプリ全体の既定値でリサイズする() throws Exception {
        world.bindProject(1L, 7L, null, null, 1L);
        world.media.on("GET", "/api/internal/media/projects/\\d+/article-image-long-edge-px",
                Response.empty(200));
        List<MultipartFile> images = List.of(
                new MockMultipartFile("images", "eyecatch.png", "image/png", renderPng(1500, 100)));

        service.publish(command("my-article", "My Article", images, null, List.of("assets/eyecatch.png")));

        BufferedImage uploaded = ImageIO.read(new ByteArrayInputStream(uploaded("my-article-0001.jpg").bytes));
        assertEquals(1300, uploaded.getWidth());
    }

    private byte[] renderPng(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private String sha256Hex(byte[] data) {
        return com.letsblog.publishing.cms.MediaContentHash.sha256Hex(data);
    }

    @Test
    void publish_再投稿時に内容が同じ画像は再アップロードしない() {
        String sha256 = sha256Hex(new byte[]{1});
        world.stubContentPost(1L, "55", "my-article", uploadedImagesJson(
                "assets/eyecatch.png", sha256, "https://example.com/wp-content/uploads/1.png", "11"));
        world.ssh.addPost("55", "my-article", "draft");
        world.ssh.addMedia("11", "https://example.com/wp-content/uploads/1.png", new byte[]{1});

        List<MultipartFile> images = List.of(
                new MockMultipartFile("images", "eyecatch.png", "image/png", new byte[]{1}));
        PostPublishCommand command = new PostPublishCommand(
                "main", "My Article", "my-article", "draft", List.of(), List.of(), "55", "本文", images, null,
                List.of("assets/eyecatch.png"), null);

        service.publish(command);

        assertFalse(world.ssh.ran("media import"), "同一内容の画像は再アップロードしない");
        assertEquals(1, world.ssh.media.size());
    }

    @Test
    void publish_再投稿時に内容が異なる画像は再アップロードされる() {
        String oldSha256 = sha256Hex(new byte[]{9, 9, 9});
        world.stubContentPost(1L, "55", "my-article", uploadedImagesJson(
                "assets/eyecatch.png", oldSha256, "https://example.com/old.png", "1"));
        world.ssh.addPost("55", "my-article", "draft");

        List<MultipartFile> images = List.of(
                new MockMultipartFile("images", "eyecatch.png", "image/png", new byte[]{1}));
        PostPublishCommand command = new PostPublishCommand(
                "main", "My Article", "my-article", "draft", List.of(), List.of(), "55", "本文", images, null,
                List.of("assets/eyecatch.png"), null);

        service.publish(command);

        assertTrue(wasUploaded("my-article-0001.png"));
    }

    @Test
    void publish_既存投稿がCMS側に存在しない場合は画像キャッシュを再利用せず再アップロードする() {
        String sha256 = sha256Hex(new byte[]{1});
        world.stubContentPost(1L, "55", "my-article", uploadedImagesJson(
                "assets/eyecatch.png", sha256, "https://example.com/wp-content/uploads/1.png", "11"));
        // wpPostId=55の投稿も、メディア11もCMS側には無い。

        List<MultipartFile> images = List.of(
                new MockMultipartFile("images", "eyecatch.png", "image/png", new byte[]{1}));
        PostPublishCommand command = new PostPublishCommand(
                "main", "My Article", "my-article", "draft", List.of(), List.of(), "55", "本文", images, null,
                List.of("assets/eyecatch.png"), null);

        PostPublishResponse response = service.publish(command);

        assertTrue(wasUploaded("my-article-0001.png"));
        assertNotEquals("55", response.wpPostId(), "実在しない投稿IDは新規作成にフォールバックする");
        assertTrue(world.ssh.posts.containsKey(response.wpPostId()));
    }

    @Test
    void publish_ハッシュが一致してもメディアがCMS側に実在しなければ再アップロードする() {
        String sha256 = sha256Hex(new byte[]{1});
        world.stubContentPost(1L, "55", "my-article", uploadedImagesJson(
                "assets/eyecatch.png", sha256, "https://example.com/wp-content/uploads/1.png", "11"));
        world.ssh.addPost("55", "my-article", "draft");
        // メディア11はCMS側に実在しない。

        List<MultipartFile> images = List.of(
                new MockMultipartFile("images", "eyecatch.png", "image/png", new byte[]{1}));
        PostPublishCommand command = new PostPublishCommand(
                "main", "My Article", "my-article", "draft", List.of(), List.of(), "55", "本文", images, null,
                List.of("assets/eyecatch.png"), null);

        service.publish(command);

        assertTrue(wasUploaded("my-article-0001.png"));
    }

    private PostPublishCommand scheduledCommand(String publishScheduledAt) {
        return scheduledCommand(publishScheduledAt, "draft");
    }

    private PostPublishCommand scheduledCommand(String publishScheduledAt, String status) {
        return new PostPublishCommand(
                "main", "My Article", "my-article", status, List.of(), List.of(), null, "本文", List.of(), null,
                List.of(), publishScheduledAt);
    }

    /** siteId=1 を本番サイトに持つプロジェクトを紐づける。 */
    private void bindProductionSite() {
        world.bindProject(1L, 7L, null, null, 1L);
    }

    /** siteId=1 をテスト環境に持つ(本番は別サイト)プロジェクトを紐づける。 */
    private void bindNonProductionSite() {
        world.bindProject(1L, 7L, null, 1L, 99L);
    }

    @Test
    void publish_本番サイトへの投稿ではcontent_serviceへisProductionSite_trueを渡す() {
        bindProductionSite();

        service.publish(command("slug", "title", List.of(), null));

        assertEquals(7L, preImageRequest().path("projectId").asLong());
        assertTrue(preImageRequest().path("productionSite").asBoolean());
    }

    @Test
    void publish_本番以外のサイトへの投稿ではcontent_serviceへisProductionSite_falseを渡す() {
        bindNonProductionSite();

        service.publish(command("slug", "title", List.of(), null));

        assertEquals(7L, preImageRequest().path("projectId").asLong());
        assertFalse(preImageRequest().path("productionSite").asBoolean());
    }

    @Test
    void publish_本番サイトでは公開予定日時がstatus_futureとして送られる() {
        bindProductionSite();
        String scheduledAt = OffsetDateTime.now(ZoneOffset.UTC).plusDays(1).withNano(0).toString();

        PostPublishResponse response = service.publish(scheduledCommand(scheduledAt));

        FakeWordPress.Post post = created(response);
        assertEquals("future", post.status);
        assertEquals(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .format(OffsetDateTime.parse(scheduledAt).toInstant().atOffset(ZoneOffset.UTC)), post.dateGmt);
    }

    @Test
    void publish_本番以外のサイトでは公開予定日時を無視する() {
        bindNonProductionSite();
        String scheduledAt = OffsetDateTime.now(ZoneOffset.UTC).plusDays(1).toString();

        PostPublishResponse response = service.publish(scheduledCommand(scheduledAt));

        FakeWordPress.Post post = created(response);
        assertEquals("draft", post.status);
        assertNull(post.dateGmt);
    }

    @Test
    void publish_本番サイトで過去の公開予定日時かつstatus_publishの場合は無視して即時公開する() {
        bindProductionSite();
        String past = OffsetDateTime.now(ZoneOffset.UTC).minusDays(1).toString();

        PostPublishResponse response = service.publish(scheduledCommand(past, "publish"));

        FakeWordPress.Post post = created(response);
        assertEquals("publish", post.status);
        assertNull(post.dateGmt);
    }

    @Test
    void publish_本番サイトで過去の公開予定日時かつstatus_publish以外の場合は無視して非公開のまま投稿する() {
        bindProductionSite();
        String past = OffsetDateTime.now(ZoneOffset.UTC).minusDays(1).toString();

        PostPublishResponse response = service.publish(scheduledCommand(past, "draft"));

        FakeWordPress.Post post = created(response);
        assertEquals("draft", post.status);
        assertNull(post.dateGmt);
    }

    @Test
    void publish_本番以外のサイトでは過去の公開予定日時も無視する() {
        bindNonProductionSite();
        String past = OffsetDateTime.now(ZoneOffset.UTC).minusDays(1).toString();

        PostPublishResponse response = service.publish(scheduledCommand(past));

        FakeWordPress.Post post = created(response);
        assertEquals("draft", post.status);
        assertNull(post.dateGmt);
    }

    @Test
    void publish_ISO8601以外の公開予定日時は拒否する() {
        bindProductionSite();

        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class, () -> service.publish(scheduledCommand("2026/12/25 09:00")));
        assertTrue(e.getMessage().contains("ISO 8601"));
        assertTrue(world.ssh.posts.isEmpty());
    }

    @Test
    void publish_公開予定日時が空文字の場合は通常投稿として扱う() {
        bindProductionSite();

        PostPublishResponse response = service.publish(scheduledCommand("  "));

        FakeWordPress.Post post = created(response);
        assertEquals("draft", post.status);
        assertNull(post.dateGmt);
    }

    @Test
    void publish_rechartsタグが不正な場合は投稿を拒否する() {
        world.content.status("POST", "/api/internal/content/render/pre-image", 400, "type属性は必須です");

        InvalidRechartsTagException e = assertThrows(InvalidRechartsTagException.class,
                () -> service.publish(command("my-article", "My Article", List.of(), null)));
        assertTrue(e.getMessage().contains("type属性"));
        assertTrue(world.ssh.posts.isEmpty());
    }

    @Test
    void publish_content_serviceのレンダリングが失敗したら投稿しない() {
        world.content.status("POST", "/api/internal/content/render/pre-image", 500, "boom");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.publish(command("my-article", "My Article", List.of(), null)));
        assertTrue(e.getMessage().contains("content-service"));
        assertTrue(world.ssh.posts.isEmpty());
    }

    @Test
    void publish_plantumlタグをレンダリングしCMSへアップロードする() {
        preImageReturns("[plantuml]\nA->B\n[/plantuml]");
        world.media.on("POST", "/api/render/plantuml", Response.bytes(200, "image/png", new byte[]{1}));

        service.publish(command("my-article", "My Article", List.of(), null));

        assertEquals("![diagram](" + uploaded("plantuml-tag-1.png").url + ")", finalizedMarkdown());
    }

    @Test
    void publish_plantumlタグが不正な場合は投稿を拒否する() {
        preImageReturns("[plantuml]\nA->\n[/plantuml]");
        world.media.status("POST", "/api/render/plantuml", 400,
                "{\"error\":\"PlantUML が図を描けませんでした: Syntax Error\"}");

        PlantUmlRenderRejectedException e = assertThrows(PlantUmlRenderRejectedException.class,
                () -> service.publish(command("my-article", "My Article", List.of(), null)));
        assertTrue(e.getMessage().contains("PlantUML"));
        assertTrue(world.ssh.posts.isEmpty());
    }

    @Test
    void publish_前回投稿時のPlantUMLキャッシュはtagRenderServiceとembedServiceへ引き継がれる() {
        String tagSha = sha256Hex("@startuml\nA->B\n@enduml".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String fenceSha = sha256Hex("@startuml\nC->D\n@enduml".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        world.stubContentPost(1L, "55", "my-article", "{\"plantuml:" + tagSha + "\":{\"sha256\":\"" + tagSha
                + "\",\"url\":\"https://example.com/tag.png\",\"mediaId\":\"9\"},\"plantuml:" + fenceSha
                + "\":{\"sha256\":\"" + fenceSha + "\",\"url\":\"https://example.com/fence.png\",\"mediaId\":\"10\"}}");
        world.ssh.addPost("55", "my-article", "draft");
        world.ssh.addMedia("9", "https://example.com/tag.png", new byte[]{1});
        world.ssh.addMedia("10", "https://example.com/fence.png", new byte[]{2});
        preImageReturns("[plantuml]\nA->B\n[/plantuml]\n```plantuml\nC->D\n```");

        PostPublishCommand command = new PostPublishCommand(
                "main", "My Article", "my-article", "draft", List.of(), List.of(), "55", "本文", List.of(), null,
                List.of(), null);
        service.publish(command);

        assertTrue(world.media.requests().isEmpty(), "キャッシュがあるので再レンダリングしない");
        assertEquals("![diagram](https://example.com/tag.png)\n![diagram](https://example.com/fence.png)",
                finalizedMarkdown());
        String json = upsert().path("uploadedImagesJson").asText();
        assertTrue(json.contains("plantuml:" + tagSha));
        assertTrue(json.contains("plantuml:" + fenceSha));
    }

    @Test
    void publish_PlantUMLダイアグラムのアップロード結果がcontent_service反映時のキャッシュに含まれる() {
        preImageReturns("```plantuml\nA->B\n```");
        world.media.on("POST", "/api/render/plantuml", Response.bytes(200, "image/png", new byte[]{1}));

        service.publish(command("my-article", "My Article", List.of(), null));

        String sha = sha256Hex("@startuml\nA->B\n@enduml".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(upsert().path("uploadedImagesJson").asText().contains("plantuml:" + sha));
    }

    @Test
    void publish_categoriesがcontent_serviceへの反映呼び出しに含まれる() {
        PostPublishCommand command = new PostPublishCommand(
                "main", "My Article", "my-article", "draft", List.of("技術", "お知らせ"), List.of(), null, "本文",
                List.of(), null, List.of(), null);

        service.publish(command);

        String categories = upsert().path("categories").asText();
        assertTrue(categories.contains("技術"));
        assertTrue(categories.contains("お知らせ"));
        assertTrue(world.ssh.terms.containsKey("category:技術"), "カテゴリはWordPressで解決(作成)される");
    }

    @Test
    void publish_publishScheduledAtがcontent_serviceへの反映呼び出しに含まれる() {
        bindProductionSite();
        OffsetDateTime scheduledAt = OffsetDateTime.now(ZoneOffset.UTC).plusDays(1).withNano(0);

        service.publish(scheduledCommand(scheduledAt.toString()));

        LocalDateTime sent = LocalDateTime.parse(upsert().path("publishScheduledAt").asText());
        assertEquals(scheduledAt.toInstant(), sent.toInstant(ZoneOffset.UTC));
    }

    @Test
    void publish_プロジェクトメンバーでもadminでもなければCMSへ触れずに拒否する() {
        // issue #830: WordPressへの公開が「認証済みなら誰でも」通っていた。CMSへの副作用
        // (画像アップロード・投稿作成)が始まる前に弾かれることを確かめる。
        world.bindProject(1L, 7L, null, null, 1L);
        world.stubActor("Bearer member-token", 10L, "user", "m@example.com").stubProjectMember(7L, 10L, false);
        world.asUser("Bearer member-token");

        assertThrows(ForbiddenException.class,
                () -> service.publish(command("my-article", "My Article", List.of(), null)));

        assertTrue(world.ssh.commands.stream().noneMatch(c -> c.contains("post create")));
        assertTrue(world.content.requests("POST", "/api/internal/content/render/pre-image").isEmpty());
        verify(world.rabbitTemplate, never()).convertAndSend(any(String.class), any(String.class), any(Object.class));
    }

    // ---- issue #1431: WordPress側のスラッグ照会 ----

    private PostPublishCommand commandWithWpPostId(String slug, String wpPostId) {
        return new PostPublishCommand(
                "main", "My Article", slug, "draft", List.of(), List.of(), wpPostId, "本文", List.of(), null,
                null, null);
    }

    @Test
    void publish_wpPostIdが無くWordPressに同じスラッグの投稿が1件あればその投稿を更新しpostsへupsertする() {
        world.ssh.addPost("55", "my-article", "draft");

        PostPublishResponse response = service.publish(commandWithWpPostId("my-article", null));

        assertEquals("55", response.wpPostId());
        assertEquals(1, world.ssh.posts.size(), "新規作成せず既存投稿を更新する");
        JsonNode body = upsert();
        assertEquals("55", body.path("wpPostId").asText());
        assertEquals("my-article", body.path("slug").asText());
        assertEquals("draft", body.path("status").asText());
    }

    @Test
    void publish_wpPostIdが空文字でも未指定と同じく照会する() {
        world.ssh.addPost("55", "my-article", "draft");

        service.publish(commandWithWpPostId("my-article", " "));

        assertEquals(1, world.ssh.posts.size());
        assertTrue(world.ssh.ran("--name='my-article'"));
    }

    @Test
    void publish_WordPressに同じスラッグの投稿が無ければ新規作成する() {
        PostPublishResponse response = service.publish(commandWithWpPostId("my-article", null));

        assertTrue(world.ssh.ran("--name='my-article'"), "スラッグで照会する");
        assertTrue(world.ssh.ran("post create"));
        assertEquals("my-article", created(response).slug);
    }

    @Test
    void publish_slug未指定なら照会せず新規作成する() {
        service.publish(commandWithWpPostId(null, null));

        assertFalse(world.ssh.ran("--name="), "スラッグが無ければ照会しない");
        assertTrue(world.ssh.ran("post create"));
    }

    @Test
    void publish_slugが空白なら照会せず新規作成する() {
        service.publish(commandWithWpPostId("  ", null));

        assertFalse(world.ssh.ran("--name="));
        assertTrue(world.ssh.ran("post create"));
    }

    @Test
    void publish_wpPostIdが指定されていれば照会しない() {
        world.ssh.addPost("55", "my-article", "draft");

        service.publish(commandWithWpPostId("my-article", "55"));

        assertFalse(world.ssh.ran("--name="));
        assertTrue(world.ssh.ran("post update '55'"));
    }

    @Test
    void publish_同じスラッグの投稿が複数あれば候補IDを示して中止し作成も更新もしない() {
        world.ssh.addPost("55", "my-article", "draft");
        world.ssh.addPost("56", "my-article", "publish");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service.publish(commandWithWpPostId("my-article", null)));

        assertTrue(thrown.getMessage().contains("55"));
        assertTrue(thrown.getMessage().contains("56"));
        assertFalse(world.ssh.ran("post create"));
        assertFalse(world.ssh.ran("post update"));
        assertTrue(world.content.requests("PUT", "/api/internal/content/posts").isEmpty());
    }

    @Test
    void publish_照会に失敗したら新規作成へ進まず中止する() {
        world.ssh.whenFail("--name=", "ssh timeout");

        assertThrows(RuntimeException.class, () -> service.publish(commandWithWpPostId("my-article", null)));

        assertFalse(world.ssh.ran("post create"));
    }

    // ---- issue #1432: WordPress側の内容ハッシュ(sha256)照会によるメディア再利用 ----

    private PostPublishCommand imageCommand(String featured, List<byte[]> contents, List<String> refs) {
        List<MultipartFile> images = new java.util.ArrayList<>();
        for (int i = 0; i < contents.size(); i++) {
            images.add(new MockMultipartFile("images", "img" + i + ".png", "image/png", contents.get(i)));
        }
        return new PostPublishCommand(
                "main", "My Article", "my-article", "draft", List.of(), List.of(), null, "本文", images, featured,
                refs, null);
    }

    @Test
    void publish_ローカルに記録が無くてもWordPress側に同一sha256のメディアがあれば再アップロードせず既存URLを使う() {
        world.ssh.addMedia("88", "https://example.com/wp-content/uploads/existing.png", new byte[]{1});
        preImageReturns("![a](assets/eyecatch.png)");

        service.publish(imageCommand(null, List.of(new byte[]{1}), List.of("assets/eyecatch.png")));

        assertFalse(world.ssh.ran("media import"));
        assertTrue(finalizedMarkdown().contains("https://example.com/wp-content/uploads/existing.png"));
    }

    @Test
    void publish_アイキャッチに指定した画像もWordPress側の既存メディアを再利用しfeaturedMediaIdになる() {
        world.ssh.addMedia("88", "https://example.com/wp-content/uploads/existing.png", new byte[]{1});

        PostPublishResponse response = service.publish(
                imageCommand("assets/eyecatch.png", List.of(new byte[]{1}), List.of("assets/eyecatch.png")));

        assertEquals("88", created(response).thumbnailId);
        assertFalse(world.ssh.ran("media import"));
    }

    @Test
    void publish_WordPress側に同一sha256が無ければ新規アップロードする() {
        service.publish(imageCommand(null, List.of(new byte[]{1}), List.of("assets/eyecatch.png")));

        assertTrue(wasUploaded("my-article-0001.png"));
    }

    @Test
    void publish_照会は画像の枚数によらず1回で全画像のsha256をまとめて渡す() {
        service.publish(imageCommand(null, List.of(new byte[]{1}, new byte[]{2}, new byte[]{3}),
                List.of("a.png", "b.png", "c.png")));

        List<String> lookups = world.ssh.commands.stream().filter(c -> c.contains("get_posts")).toList();
        assertEquals(1, lookups.size(), "照会は1回だけ: " + lookups);
        for (byte b = 1; b <= 3; b++) {
            assertTrue(lookups.get(0).contains(sha256Hex(new byte[]{b})));
        }
    }

    @Test
    void publish_ローカルのキャッシュで再利用できる画像だけなら照会しない() {
        String sha = sha256Hex(new byte[]{1});
        world.stubContentPost(1L, "55", "my-article", uploadedImagesJson(
                "assets/eyecatch.png", sha, "https://example.com/wp-content/uploads/1.png", "11"));
        world.ssh.addPost("55", "my-article", "draft");
        world.ssh.addMedia("11", "https://example.com/wp-content/uploads/1.png", new byte[]{1});
        PostPublishCommand command = new PostPublishCommand(
                "main", "My Article", "my-article", "draft", List.of(), List.of(), "55", "本文",
                List.of(new MockMultipartFile("images", "e.png", "image/png", new byte[]{1})), null,
                List.of("assets/eyecatch.png"), null);

        service.publish(command);

        assertFalse(world.ssh.ran("get_posts"), "WordPressへの内容ハッシュ照会は行わない");
        assertFalse(world.ssh.ran("media import"));
    }

    @Test
    void publish_WordPress側で再利用したメディアもuploadedImagesJsonに記録する() {
        String sha = sha256Hex(new byte[]{1});
        world.ssh.addMedia("88", "https://example.com/wp-content/uploads/existing.png", new byte[]{1});

        service.publish(imageCommand(null, List.of(new byte[]{1}), List.of("assets/eyecatch.png")));

        String json = upsert().path("uploadedImagesJson").asText();
        assertTrue(json.contains("\"mediaId\":\"88\""));
        assertTrue(json.contains(sha));
    }

    @Test
    void publish_照会に失敗しても従来どおり新規アップロードして投稿を続行する() {
        world.ssh.whenFail("get_posts", "ssh timeout");

        PostPublishResponse response =
                service.publish(imageCommand(null, List.of(new byte[]{1}), List.of("assets/eyecatch.png")));

        assertTrue(world.ssh.posts.containsKey(response.wpPostId()));
        assertTrue(wasUploaded("my-article-0001.png"));
    }

    @Test
    void publish_内容が異なる画像は同じ参照名でも既存メディアを再利用せず新規アップロードする() {
        // WordPress側にあるのは別内容(sha256が異なる)のメディアだけ。
        world.ssh.addMedia("88", "https://example.com/wp-content/uploads/other.png", new byte[]{9});

        PostPublishResponse response = service.publish(
                imageCommand("assets/eyecatch.png", List.of(new byte[]{1}), List.of("assets/eyecatch.png")));

        assertEquals(uploaded("my-article-0001.png").id, created(response).thumbnailId);
        assertNotEquals("88", created(response).thumbnailId);
    }

    // ---- issue #1557: プラグインが使えないサイトへの投稿は拒否する ----

    @Test
    void publish_letsblogプラグインが使えないサイトは拒否しCMSへ何も書き込まない() {
        world.ssh.site().letsblogStatusOutput = null;

        LetsblogPluginUnavailableException e = assertThrows(LetsblogPluginUnavailableException.class,
                () -> service.publish(command("slug", "title", List.of(), null)));

        assertTrue(e.getMessage().contains("再導入"));
        assertTrue(world.ssh.posts.isEmpty());
        assertTrue(world.ssh.media.isEmpty());
        assertTrue(world.ssh.terms.isEmpty(), "カテゴリ・タグの解決にも進まない");
        verify(world.rabbitTemplate, never()).convertAndSend(any(String.class), any(String.class), any(Object.class));
    }

    // ---- 画像の参照・ファイル名・アイキャッチの境界(issue #1815) ----

    @Test
    void publish_imageReferencesの一部がnullなら_その画像はoriginalFilenameを参照にする() {
        preImageReturns("![a](assets/a.png)\n![b](b.png)");
        List<MultipartFile> images = List.of(
                new MockMultipartFile("images", "a.png", "image/png", new byte[]{1}),
                new MockMultipartFile("images", "b.png", "image/png", new byte[]{2}));

        service.publish(command("my-article", "My Article", images, null,
                java.util.Arrays.asList("assets/a.png", null)));

        assertEquals("![a](" + uploaded("my-article-0001.png").url + ")\n![b](" + uploaded("my-article-0002.png").url
                + ")", finalizedMarkdown());
    }

    @Test
    void publish_参照もoriginalFilenameも無い画像は読み飛ばし_他の画像だけをアップロードする() {
        List<MultipartFile> images = List.of(
                new com.letsblog.publishing.service.BytesMultipartFile("images", null, "image/png", new byte[]{1}),
                new MockMultipartFile("images", "b.png", "image/png", new byte[]{2}));

        service.publish(command("my-article", "My Article", images, null, null));

        assertEquals(1, world.ssh.media.size());
        assertTrue(wasUploaded("my-article-0002.png"), "連番は元の並びの位置のまま");
    }

    @Test
    void publish_アイキャッチに指定した参照がどの画像とも一致しなければfeaturedMediaIdはnullのまま投稿する() {
        List<MultipartFile> images = List.of(
                new MockMultipartFile("images", "a.png", "image/png", new byte[]{1}));

        PostPublishResponse response = service.publish(command("my-article", "My Article", images, "other.png",
                List.of("assets/a.png")));

        assertNull(created(response).thumbnailId);
        assertTrue(wasUploaded("my-article-0001.png"));
    }

    @Test
    void publish_slugが空白ならtitleからファイル名を作る() {
        List<MultipartFile> images = List.of(
                new MockMultipartFile("images", "a.png", "image/png", new byte[]{1}));

        service.publish(command("   ", "Hello World", images, null));

        assertTrue(wasUploaded("hello-world-0001.png"));
    }

    @Test
    void publish_拡張子の無い参照のPNG以外の画像は拡張子binでアップロードする() {
        List<MultipartFile> images = List.of(
                new MockMultipartFile("images", "noext", "image/webp", new byte[]{1}));

        service.publish(command("my-article", "My Article", images, null, List.of("noext")));

        assertTrue(wasUploaded("my-article-0001.bin"));
    }

    @Test
    void publish_カテゴリもタグも未指定ならWordPressのタームは作らずpostsへのカテゴリも空で反映する() {
        PostPublishCommand command = new PostPublishCommand(
                "main", "My Article", "my-article", "draft", null, null, null, "本文", List.of(), null, List.of(),
                null);

        PostPublishResponse response = service.publish(command);

        assertTrue(world.ssh.terms.isEmpty());
        assertTrue(upsert().path("categories").isNull());
        assertTrue(world.ssh.posts.containsKey(response.wpPostId()));
    }
}
