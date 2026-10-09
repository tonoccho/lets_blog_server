package com.letsblog.publishing.service;

import com.letsblog.publishing.client.ContentServiceClient;
import com.letsblog.publishing.client.IdentityBridgeClient;
import com.letsblog.publishing.client.MediaSettingsBridgeClient;
import com.letsblog.publishing.client.ProjectServiceClient;
import com.letsblog.publishing.cms.CmsAdapter;
import com.letsblog.publishing.cms.CmsAdapterFactory;
import com.letsblog.publishing.cms.CmsCredentials;
import com.letsblog.publishing.cms.CmsType;
import com.letsblog.publishing.cms.MediaUploadResult;
import com.letsblog.publishing.cms.PostContent;
import com.letsblog.publishing.cms.PostResult;
import com.letsblog.publishing.dto.PostPublishCommand;
import com.letsblog.publishing.dto.PostPublishResponse;
import com.letsblog.publishing.messaging.DomainEventPublisher;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PostPublishServiceの回帰テスト(legacy-apiから移設。issue #707)。画像ファイル名リネーム
 * ({slug}-{4桁連番}.{拡張子})とfeatured_image指定時のfeatured_media解決を中心に検証する。サイト本体・
 * CMS認証情報はProjectServiceClient、カスタムタグ/組み込みタグの展開・HTML変換・postsテーブルの
 * 読み書きはContentServiceClient、著者マッピング(user_site_authors)はIdentityBridgeClient経由に
 * なったため(#575設計判断1・2・4)、それらのモックへ差し替えている。
 */
@ExtendWith(MockitoExtension.class)
class PostPublishServiceTest {

    @Mock
    private ProjectServiceClient projectServiceClient;
    @Mock
    private CmsAdapterFactory cmsAdapterFactory;
    @Mock
    private ContentServiceClient contentServiceClient;
    @Mock
    private PlantUmlEmbedService plantUmlEmbedService;
    @Mock
    private PlantUmlTagRenderService plantUmlTagRenderService;
    @Mock
    private CurrentActorService currentActorService;
    @Mock
    private IdentityBridgeClient identityBridgeClient;

    @Mock
    private MediaSettingsBridgeClient mediaSettingsBridgeClient;
    @Mock
    private CmsAdapter cmsAdapter;
    @Mock
    private DomainEventPublisher domainEventPublisher;
    @Mock
    private AdminAuthorizationService adminAuthorizationService;

    private PostPublishService service;

    private final CmsCredentials.WordPressCredentials credentials =
            new CmsCredentials.WordPressCredentials("https://example.com", "admin", "SSH");

    @BeforeEach
    void setUp() {
        service = new PostPublishService(projectServiceClient, cmsAdapterFactory, contentServiceClient,
                plantUmlEmbedService, plantUmlTagRenderService,
                currentActorService, identityBridgeClient, mediaSettingsBridgeClient,
                new com.fasterxml.jackson.databind.ObjectMapper(), new ImageResizeService(), domainEventPublisher,
                adminAuthorizationService);

        ProjectServiceClient.SiteBridge site =
                new ProjectServiceClient.SiteBridge(1L, "main", "Main", "https://example.com", CmsType.WORDPRESS, false, null);
        ProjectServiceClient.SiteCredentialsBridge credentialsBridge = new ProjectServiceClient.SiteCredentialsBridge(
                1L, CmsType.WORDPRESS,
                Map.of("baseUrl", "https://example.com", "username", "admin", "transport", "SSH"));

        lenient().when(projectServiceClient.getSiteByKey("main")).thenReturn(site);
        lenient().when(projectServiceClient.getCredentials("main")).thenReturn(credentialsBridge);
        lenient().when(cmsAdapterFactory.resolve(CmsType.WORDPRESS)).thenReturn(cmsAdapter);
        lenient().when(projectServiceClient.findProjectIdBySiteId(1L)).thenReturn(null);
        lenient().when(contentServiceClient.renderPreImage(anyString(), any(), anyBoolean()))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(contentServiceClient.finalizeHtml(anyString(), any()))
                .thenAnswer(inv -> "<p>" + inv.getArgument(0) + "</p>");
        lenient().when(plantUmlTagRenderService.render(any(), anyString(), anyMap()))
                .thenAnswer(inv -> new DiagramEmbedResult(inv.getArgument(1), inv.getArgument(2)));
        lenient().when(plantUmlEmbedService.embedDiagrams(any(), anyString(), anyMap()))
                .thenAnswer(inv -> new DiagramEmbedResult(inv.getArgument(1), inv.getArgument(2)));
        lenient().when(cmsAdapter.resolveCategories(any(), any())).thenReturn(List.of());
        lenient().when(cmsAdapter.resolveTags(any(), any())).thenReturn(List.of());
        lenient().when(contentServiceClient.findPost(any(), any())).thenReturn(Optional.empty());
        lenient().when(cmsAdapter.postExists(any(), any())).thenReturn(true);
        lenient().when(cmsAdapter.mediaExists(any(), any())).thenReturn(true);
        lenient().when(currentActorService.getCurrentActorId()).thenReturn(null);
        lenient().when(identityBridgeClient.findUserSiteAuthor(any(), any())).thenReturn(Optional.empty());
        lenient().when(mediaSettingsBridgeClient.resolveArticleImageLongEdgePx(any())).thenReturn(1300);
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

    private ContentServiceClient.PostBridgeResponse bridgePost(String wpPostId, String uploadedImagesJson) {
        return new ContentServiceClient.PostBridgeResponse(
                1L, wpPostId, "my-article", "draft", uploadedImagesJson, null, null, null);
    }

    @Test
    void publish_画素数が上限を超える添付画像は断り_WordPressへ何も送らない() {
        List<MultipartFile> images = List.of(new MockMultipartFile(
                "images", "bomb.png", "image/png", OversizedImageFixtures.pngHeaderOnly(30000, 30000)));

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.publish(command("my-article", "My Article", images, null)));

        assertTrue(e.getMessage().contains("画素数"), e.getMessage());
        verify(cmsAdapter, never()).uploadMedia(any(), any(), any(), any());
        verify(cmsAdapter, never()).createOrUpdatePost(any(), any(), any());
    }

    @Test
    void publish_画像が複数ある場合はslug連番でリネームされる() {
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("101", "https://example.com/?p=101", "draft"));
        when(cmsAdapter.uploadMedia(any(), eq("my-article-0001.png"), any(), any()))
                .thenReturn(new MediaUploadResult("11", "https://example.com/wp-content/uploads/1.png"));
        when(cmsAdapter.uploadMedia(any(), eq("my-article-0002.jpg"), any(), any()))
                .thenReturn(new MediaUploadResult("12", "https://example.com/wp-content/uploads/2.jpg"));

        List<MultipartFile> images = List.of(
                new MockMultipartFile("images", "eyecatch.png", "image/png", new byte[]{1}),
                new MockMultipartFile("images", "photo.jpg", "image/jpeg", new byte[]{2}));

        PostPublishResponse response = service.publish(command("my-article", "My Article", images, null));

        assertEquals("101", response.wpPostId());
        verify(cmsAdapter).uploadMedia(eq(credentials), eq("my-article-0001.png"), eq("image/png"), any());
        verify(cmsAdapter).uploadMedia(eq(credentials), eq("my-article-0002.jpg"), eq("image/jpeg"), any());
    }

    @Test
    void publish_既存スラッグのwpPostIdを渡して再投稿するとcontent_serviceへ同じwpPostIdで反映を依頼する() {
        when(contentServiceClient.findPost(1L, "55")).thenReturn(Optional.of(bridgePost("55", null)));
        when(cmsAdapter.createOrUpdatePost(any(), any(), eq("55")))
                .thenReturn(new PostResult("55", "https://example.com/?p=55", "draft"));

        PostPublishCommand command = new PostPublishCommand(
                "main", "My Article", "my-article", "draft", List.of(), List.of(), "55", "本文", List.of(), null,
                null, null);

        service.publish(command);

        verify(contentServiceClient).upsertPost(
                eq(1L), eq("55"), eq("my-article"), eq("draft"), any(), any(), any());
        verify(domainEventPublisher).publishPostPublished(
                eq(1L), any(), eq("55"), eq("https://example.com/?p=55"), eq("draft"));
    }

    @Test
    void publish_slug未指定時はtitleから簡易スラッグ化される() {
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("101", "https://example.com/?p=101", "draft"));
        when(cmsAdapter.uploadMedia(any(), eq("hello-world-blog-0001.png"), any(), any()))
                .thenReturn(new MediaUploadResult("11", "https://example.com/wp-content/uploads/1.png"));

        List<MultipartFile> images = List.of(
                new MockMultipartFile("images", "eyecatch.png", "image/png", new byte[]{1}));

        service.publish(command(null, "Hello, World! Blog", images, null));

        verify(cmsAdapter).uploadMedia(eq(credentials), eq("hello-world-blog-0001.png"), any(), any());
    }

    @Test
    void publish_titleが特殊文字のみの場合はpostがデフォルトになる() {
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("101", "https://example.com/?p=101", "draft"));
        when(cmsAdapter.uploadMedia(any(), eq("post-0001.png"), any(), any()))
                .thenReturn(new MediaUploadResult("11", "https://example.com/wp-content/uploads/1.png"));

        List<MultipartFile> images = List.of(
                new MockMultipartFile("images", "eyecatch.png", "image/png", new byte[]{1}));

        service.publish(command(null, "!!!###", images, null));

        verify(cmsAdapter).uploadMedia(eq(credentials), eq("post-0001.png"), any(), any());
    }

    @Test
    void publish_元ファイル名の拡張子が保持される() {
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("101", "https://example.com/?p=101", "draft"));
        when(cmsAdapter.uploadMedia(any(), eq("my-article-0001.gif"), any(), any()))
                .thenReturn(new MediaUploadResult("11", "https://example.com/wp-content/uploads/1.gif"));

        List<MultipartFile> images = List.of(
                new MockMultipartFile("images", "anim.gif", "image/gif", new byte[]{1}));

        service.publish(command("my-article", "My Article", images, null));

        verify(cmsAdapter).uploadMedia(eq(credentials), eq("my-article-0001.gif"), any(), any());
    }

    @Test
    void publish_最終HTMLはcontent_serviceのfinalizeHtml結果がPostContentへ渡される() {
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("101", "https://example.com/?p=101", "draft"));
        when(contentServiceClient.finalizeHtml("本文", null))
                .thenReturn("<div class=\"lets-blog-rendered\">wrapped</div>");

        service.publish(command("my-article", "My Article", List.of(), null));

        verify(contentServiceClient).finalizeHtml("本文", null);
        ArgumentCaptor<PostContent> contentCaptor = ArgumentCaptor.forClass(PostContent.class);
        verify(cmsAdapter).createOrUpdatePost(eq(credentials), contentCaptor.capture(), any());
        assertEquals("<div class=\"lets-blog-rendered\">wrapped</div>", contentCaptor.getValue().htmlContent());
    }

    @Test
    void publish_featuredImageFilenameに一致する画像のfeaturedMediaIdがPostContentへ設定される() {
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("101", "https://example.com/?p=101", "draft"));
        when(cmsAdapter.uploadMedia(any(), eq("my-article-0001.png"), any(), any()))
                .thenReturn(new MediaUploadResult("11", "https://example.com/wp-content/uploads/1.png"));
        when(cmsAdapter.uploadMedia(any(), eq("my-article-0002.jpg"), any(), any()))
                .thenReturn(new MediaUploadResult("12", "https://example.com/wp-content/uploads/2.jpg"));

        List<MultipartFile> images = List.of(
                new MockMultipartFile("images", "eyecatch.png", "image/png", new byte[]{1}),
                new MockMultipartFile("images", "photo.jpg", "image/jpeg", new byte[]{2}));

        service.publish(command("my-article", "My Article", images, "eyecatch.png"));

        ArgumentCaptor<PostContent> contentCaptor = ArgumentCaptor.forClass(PostContent.class);
        verify(cmsAdapter).createOrUpdatePost(eq(credentials), contentCaptor.capture(), any());
        assertEquals("11", contentCaptor.getValue().featuredMediaId());
    }

    @Test
    void publish_アイキャッチなし投稿ではfeaturedMediaIdがnullになる() {
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("101", "https://example.com/?p=101", "draft"));

        service.publish(command("my-article", "My Article", List.of(), null));

        ArgumentCaptor<PostContent> contentCaptor = ArgumentCaptor.forClass(PostContent.class);
        verify(cmsAdapter).createOrUpdatePost(eq(credentials), contentCaptor.capture(), any());
        assertNull(contentCaptor.getValue().featuredMediaId());
    }

    @Test
    void publish_Markdown中の元ファイル名参照がアップロード後のURLに置換される() {
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("101", "https://example.com/?p=101", "draft"));
        when(cmsAdapter.uploadMedia(any(), eq("my-article-0001.png"), any(), any()))
                .thenReturn(new MediaUploadResult("11", "https://example.com/wp-content/uploads/1.png"));
        when(contentServiceClient.renderPreImage(anyString(), any(), anyBoolean())).thenReturn("![alt](eyecatch.png)");

        List<MultipartFile> images = List.of(
                new MockMultipartFile("images", "eyecatch.png", "image/png", new byte[]{1}));

        service.publish(command("my-article", "My Article", images, null));

        verify(contentServiceClient).finalizeHtml("![alt](https://example.com/wp-content/uploads/1.png)", null);
    }

    @Test
    void publish_assetsサブフォルダ参照はimageReferencesを使い二重結合されずに置換される() {
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("101", "https://example.com/?p=101", "draft"));
        when(cmsAdapter.uploadMedia(any(), eq("my-article-0001.png"), any(), any()))
                .thenReturn(new MediaUploadResult("11", "https://example.com/wp-content/uploads/1.png"));
        when(contentServiceClient.renderPreImage(anyString(), any(), anyBoolean()))
                .thenReturn("![alt](assets/eyecatch.png)");

        List<MultipartFile> images = List.of(
                new MockMultipartFile("images", "eyecatch.png", "image/png", new byte[]{1}));

        service.publish(command("my-article", "My Article", images, "assets/eyecatch.png",
                List.of("assets/eyecatch.png")));

        verify(contentServiceClient).finalizeHtml("![alt](https://example.com/wp-content/uploads/1.png)", null);
        ArgumentCaptor<PostContent> contentCaptor = ArgumentCaptor.forClass(PostContent.class);
        verify(cmsAdapter).createOrUpdatePost(eq(credentials), contentCaptor.capture(), any());
        assertEquals("11", contentCaptor.getValue().featuredMediaId());
    }

    // issue #1060: 一方の参照がもう一方の部分文字列になっている場合(eyecatch.png ⊂ assets/eyecatch.png、
    // img1.png ⊂ img10.png)に、単純なString#replaceの全件置換だと短い方が長い方の内部を書き換えてしまい、
    // 長い方の置換が黙って失敗する回帰を防ぐ。

    @Test
    void publish_短い参照が長い参照の部分文字列でもそれぞれ正しいURLに置換される() {
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("101", "https://example.com/?p=101", "draft"));
        when(cmsAdapter.uploadMedia(any(), eq("my-article-0001.png"), any(), any()))
                .thenReturn(new MediaUploadResult("11", "https://example.com/wp-content/uploads/eyecatch.png"));
        when(cmsAdapter.uploadMedia(any(), eq("my-article-0002.png"), any(), any()))
                .thenReturn(new MediaUploadResult("12", "https://example.com/wp-content/uploads/assets-eyecatch.png"));
        when(contentServiceClient.renderPreImage(anyString(), any(), anyBoolean()))
                .thenReturn("![a](eyecatch.png)\n![b](assets/eyecatch.png)");

        List<MultipartFile> images = List.of(
                new MockMultipartFile("images", "eyecatch.png", "image/png", new byte[]{1}),
                new MockMultipartFile("images", "eyecatch2.png", "image/png", new byte[]{2}));

        service.publish(command("my-article", "My Article", images, null,
                List.of("eyecatch.png", "assets/eyecatch.png")));

        verify(contentServiceClient).finalizeHtml(
                "![a](https://example.com/wp-content/uploads/eyecatch.png)\n"
                        + "![b](https://example.com/wp-content/uploads/assets-eyecatch.png)",
                null);
    }

    @Test
    void publish_imagesの並び順を入れ替えても置換結果は同一になる() {
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("101", "https://example.com/?p=101", "draft"));
        // 並び順が入れ替わったので、1番目(0001)がassets/eyecatch.png、2番目(0002)がeyecatch.pngの
        // アップロード結果になる。それでも各参照の置換先URLは、順序を入れ替える前のテストと同じでなければならない。
        when(cmsAdapter.uploadMedia(any(), eq("my-article-0001.png"), any(), any()))
                .thenReturn(new MediaUploadResult("12", "https://example.com/wp-content/uploads/assets-eyecatch.png"));
        when(cmsAdapter.uploadMedia(any(), eq("my-article-0002.png"), any(), any()))
                .thenReturn(new MediaUploadResult("11", "https://example.com/wp-content/uploads/eyecatch.png"));
        when(contentServiceClient.renderPreImage(anyString(), any(), anyBoolean()))
                .thenReturn("![a](eyecatch.png)\n![b](assets/eyecatch.png)");

        List<MultipartFile> images = List.of(
                new MockMultipartFile("images", "eyecatch2.png", "image/png", new byte[]{2}),
                new MockMultipartFile("images", "eyecatch.png", "image/png", new byte[]{1}));

        service.publish(command("my-article", "My Article", images, null,
                List.of("assets/eyecatch.png", "eyecatch.png")));

        verify(contentServiceClient).finalizeHtml(
                "![a](https://example.com/wp-content/uploads/eyecatch.png)\n"
                        + "![b](https://example.com/wp-content/uploads/assets-eyecatch.png)",
                null);
    }

    @Test
    void publish_数字接尾辞違いの参照でも短い方が長い方の内部を書き換えない() {
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("101", "https://example.com/?p=101", "draft"));
        when(cmsAdapter.uploadMedia(any(), eq("my-article-0001.png"), any(), any()))
                .thenReturn(new MediaUploadResult("11", "https://example.com/wp-content/uploads/img1.png"));
        when(cmsAdapter.uploadMedia(any(), eq("my-article-0002.png"), any(), any()))
                .thenReturn(new MediaUploadResult("12", "https://example.com/wp-content/uploads/img10.png"));
        when(contentServiceClient.renderPreImage(anyString(), any(), anyBoolean()))
                .thenReturn("![a](img1.png)\n![b](img10.png)");

        List<MultipartFile> images = List.of(
                new MockMultipartFile("images", "img1.png", "image/png", new byte[]{1}),
                new MockMultipartFile("images", "img10.png", "image/png", new byte[]{2}));

        service.publish(command("my-article", "My Article", images, null,
                List.of("img1.png", "img10.png")));

        verify(contentServiceClient).finalizeHtml(
                "![a](https://example.com/wp-content/uploads/img1.png)\n"
                        + "![b](https://example.com/wp-content/uploads/img10.png)",
                null);
    }

    @Test
    void publish_本文中に見つからない参照がある場合は警告ログを出す() {
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("101", "https://example.com/?p=101", "draft"));
        when(cmsAdapter.uploadMedia(any(), eq("my-article-0001.png"), any(), any()))
                .thenReturn(new MediaUploadResult("11", "https://example.com/wp-content/uploads/1.png"));
        when(contentServiceClient.renderPreImage(anyString(), any(), anyBoolean()))
                .thenReturn("![a](other.png)");

        List<MultipartFile> images = List.of(
                new MockMultipartFile("images", "eyecatch.png", "image/png", new byte[]{1}));

        Logger logger = (Logger) LoggerFactory.getLogger(PostPublishService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            service.publish(command("my-article", "My Article", images, null,
                    List.of("eyecatch.png")));
        } finally {
            logger.detachAppender(appender);
        }

        boolean warned = appender.list.stream()
                .anyMatch(event -> event.getLevel() == ch.qos.logback.classic.Level.WARN
                        && event.getFormattedMessage().contains("eyecatch.png"));
        assertTrue(warned, "本文中に見つからなかった参照についてWARNレベルのログが出力されること");
        verify(contentServiceClient).finalizeHtml("![a](other.png)", null);
    }

    // 以下は#1060の変更でこのファイル全体のC1/C2カバレッジがゲート(90%)を割ったために追加した、
    // 既存分岐(このIssueの変更対象ではない箇所を含む)のカバレッジ補完。CLAUDE.mdのカバレッジ節が
    // 変更後ファイル単位でゲートするため、このIssueで触れたファイルの既存の未検証分岐も対象になる。

    @Test
    void publish_statusが未指定の場合はdraftとして投稿される() {
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("101", "https://example.com/?p=101", "draft"));
        PostPublishCommand command = new PostPublishCommand(
                "main", "My Article", "my-article", null, List.of(), List.of(), null, "本文", List.of(), null,
                List.of(), null);

        service.publish(command);

        ArgumentCaptor<PostContent> contentCaptor = ArgumentCaptor.forClass(PostContent.class);
        verify(cmsAdapter).createOrUpdatePost(eq(credentials), contentCaptor.capture(), any());
        assertEquals("draft", contentCaptor.getValue().status());
    }

    @Test
    void publish_投稿済み画像情報が空文字の場合はパースせず再アップロードして続行する() {
        when(contentServiceClient.findPost(1L, "55")).thenReturn(Optional.of(bridgePost("55", "  ")));
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("55", "https://example.com/?p=55", "draft"));

        PostPublishCommand command = new PostPublishCommand(
                "main", "My Article", "my-article", "draft", List.of(), List.of(), "55", "本文", List.of(), null,
                List.of(), null);

        PostPublishResponse response = service.publish(command);

        assertEquals("55", response.wpPostId());
    }

    @Test
    void publish_発信者は判明しているがメールアドレス未設定なら著者未設定のまま続行する() {
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("101", "https://example.com/?p=101", "draft"));
        when(currentActorService.getCurrentActorId()).thenReturn(10L);
        when(currentActorService.getCurrentActorEmail()).thenReturn(null);

        service.publish(command("my-article", "My Article", List.of(), null));

        ArgumentCaptor<PostContent> contentCaptor = ArgumentCaptor.forClass(PostContent.class);
        verify(cmsAdapter).createOrUpdatePost(eq(credentials), contentCaptor.capture(), any());
        assertNull(contentCaptor.getValue().authorId());
        verify(cmsAdapter, never()).findAuthorIdByEmail(any(), any());
    }

    @Test
    void publish_imagesがnullの場合は空扱いで画像処理をスキップする() {
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("101", "https://example.com/?p=101", "draft"));

        PostPublishCommand command = new PostPublishCommand(
                "main", "My Article", "my-article", "draft", List.of(), List.of(), null, "本文", null, null,
                List.of(), null);

        PostPublishResponse response = service.publish(command);

        assertEquals("101", response.wpPostId());
    }

    @Test
    void publish_imagesが空でfeaturedImageFilenameのみ指定された場合は警告して続行する() {
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("101", "https://example.com/?p=101", "draft"));

        service.publish(command("my-article", "My Article", List.of(), "eyecatch.png"));

        ArgumentCaptor<PostContent> contentCaptor = ArgumentCaptor.forClass(PostContent.class);
        verify(cmsAdapter).createOrUpdatePost(eq(credentials), contentCaptor.capture(), any());
        assertNull(contentCaptor.getValue().featuredMediaId());
    }

    @Test
    void publish_imageReferencesがimagesより短い場合は不足分をoriginalFilenameで補う() {
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("101", "https://example.com/?p=101", "draft"));
        when(cmsAdapter.uploadMedia(any(), eq("my-article-0001.png"), any(), any()))
                .thenReturn(new MediaUploadResult("11", "https://example.com/wp-content/uploads/1.png"));
        when(cmsAdapter.uploadMedia(any(), eq("my-article-0002.png"), any(), any()))
                .thenReturn(new MediaUploadResult("12", "https://example.com/wp-content/uploads/2.png"));

        List<MultipartFile> images = List.of(
                new MockMultipartFile("images", "eyecatch.png", "image/png", new byte[]{1}),
                new MockMultipartFile("images", "photo.png", "image/png", new byte[]{2}));

        service.publish(command("my-article", "My Article", images, null, List.of("assets/eyecatch.png")));

        verify(cmsAdapter).uploadMedia(eq(credentials), eq("my-article-0001.png"), any(), any());
        verify(cmsAdapter).uploadMedia(eq(credentials), eq("my-article-0002.png"), any(), any());
    }

    @Test
    void publish_参照文字列が空文字の画像はアップロード対象から除外される() {
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("101", "https://example.com/?p=101", "draft"));

        List<MultipartFile> images = List.of(
                new MockMultipartFile("images", "eyecatch.png", "image/png", new byte[]{1}));

        service.publish(command("my-article", "My Article", images, null, List.of("  ")));

        verify(cmsAdapter, never()).uploadMedia(any(), any(), any(), any());
    }

    @Test
    void publish_slugもtitleも未指定の場合はpostがデフォルトになる() {
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("101", "https://example.com/?p=101", "draft"));
        when(cmsAdapter.uploadMedia(any(), eq("post-0001.png"), any(), any()))
                .thenReturn(new MediaUploadResult("11", "https://example.com/wp-content/uploads/1.png"));

        List<MultipartFile> images = List.of(
                new MockMultipartFile("images", "eyecatch.png", "image/png", new byte[]{1}));

        service.publish(command(null, null, images, null));

        verify(cmsAdapter).uploadMedia(eq(credentials), eq("post-0001.png"), any(), any());
    }

    @Test
    void publish_発信者に対応するWordPressユーザーが見つかればauthorIdを設定する() {
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("101", "https://example.com/?p=101", "draft"));
        when(currentActorService.getCurrentActorId()).thenReturn(10L);
        when(currentActorService.getCurrentActorEmail()).thenReturn("author@example.com");
        when(cmsAdapter.findAuthorIdByEmail(credentials, "author@example.com")).thenReturn(Optional.of("7"));

        service.publish(command("my-article", "My Article", List.of(), null));

        ArgumentCaptor<PostContent> contentCaptor = ArgumentCaptor.forClass(PostContent.class);
        verify(cmsAdapter).createOrUpdatePost(eq(credentials), contentCaptor.capture(), any());
        assertEquals("7", contentCaptor.getValue().authorId());
    }

    @Test
    void publish_user_site_authorsに対応表があればメール検索せずそれを使う() {
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("101", "https://example.com/?p=101", "draft"));
        when(currentActorService.getCurrentActorId()).thenReturn(10L);
        when(identityBridgeClient.findUserSiteAuthor(10L, 1L)).thenReturn(Optional.of("7"));

        service.publish(command("my-article", "My Article", List.of(), null));

        ArgumentCaptor<PostContent> contentCaptor = ArgumentCaptor.forClass(PostContent.class);
        verify(cmsAdapter).createOrUpdatePost(eq(credentials), contentCaptor.capture(), any());
        assertEquals("7", contentCaptor.getValue().authorId());
        verify(cmsAdapter, org.mockito.Mockito.never()).findAuthorIdByEmail(any(), any());
    }

    @Test
    void publish_メール検索で解決できた場合はuser_site_authorsへキャッシュする() {
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("101", "https://example.com/?p=101", "draft"));
        when(currentActorService.getCurrentActorId()).thenReturn(10L);
        when(currentActorService.getCurrentActorEmail()).thenReturn("author@example.com");
        when(cmsAdapter.findAuthorIdByEmail(credentials, "author@example.com")).thenReturn(Optional.of("7"));

        service.publish(command("my-article", "My Article", List.of(), null));

        verify(identityBridgeClient).cacheUserSiteAuthor(10L, 1L, "7");
    }

    @Test
    void publish_発信者不明時はauthorIdがnullのまま投稿を続行する() {
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("101", "https://example.com/?p=101", "draft"));

        service.publish(command("my-article", "My Article", List.of(), null));

        ArgumentCaptor<PostContent> contentCaptor = ArgumentCaptor.forClass(PostContent.class);
        verify(cmsAdapter).createOrUpdatePost(eq(credentials), contentCaptor.capture(), any());
        assertNull(contentCaptor.getValue().authorId());
    }

    @Test
    void publish_著者解決が例外を投げても投稿は続行される() {
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("101", "https://example.com/?p=101", "draft"));
        when(currentActorService.getCurrentActorId()).thenReturn(10L);
        when(identityBridgeClient.findUserSiteAuthor(10L, 1L)).thenThrow(new RuntimeException("接続エラー"));

        PostPublishResponse response = service.publish(command("my-article", "My Article", List.of(), null));

        assertEquals("101", response.wpPostId());
        ArgumentCaptor<PostContent> contentCaptor = ArgumentCaptor.forClass(PostContent.class);
        verify(cmsAdapter).createOrUpdatePost(eq(credentials), contentCaptor.capture(), any());
        assertNull(contentCaptor.getValue().authorId());
    }

    @Test
    void publish_長編が閾値を超える画像はリサイズしてからアップロードする() throws Exception {
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("1", "https://example.com/?p=1", "draft"));
        ArgumentCaptor<byte[]> bytesCaptor = ArgumentCaptor.forClass(byte[].class);
        when(cmsAdapter.uploadMedia(any(), eq("my-article-0001.jpg"), any(), bytesCaptor.capture()))
                .thenReturn(new MediaUploadResult("1", "https://example.com/wp-content/uploads/1.jpg"));
        when(mediaSettingsBridgeClient.resolveArticleImageLongEdgePx(any())).thenReturn(100);

        List<MultipartFile> images = List.of(
                new MockMultipartFile("images", "eyecatch.png", "image/png", renderPng(400, 200)));
        PostPublishCommand command = new PostPublishCommand(
                "main", "My Article", "my-article", "draft", List.of(), List.of(), null, "本文", images, null,
                List.of("assets/eyecatch.png"), null);

        service.publish(command);

        BufferedImage uploaded = ImageIO.read(new ByteArrayInputStream(bytesCaptor.getValue()));
        assertEquals(100, uploaded.getWidth());
        assertEquals(50, uploaded.getHeight());
    }

    @Test
    void publish_長編が閾値以下の画像はリサイズしない() throws Exception {
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("1", "https://example.com/?p=1", "draft"));
        ArgumentCaptor<byte[]> bytesCaptor = ArgumentCaptor.forClass(byte[].class);
        when(cmsAdapter.uploadMedia(any(), eq("my-article-0001.jpg"), any(), bytesCaptor.capture()))
                .thenReturn(new MediaUploadResult("1", "https://example.com/wp-content/uploads/1.jpg"));
        when(mediaSettingsBridgeClient.resolveArticleImageLongEdgePx(any())).thenReturn(1300);

        List<MultipartFile> images = List.of(
                new MockMultipartFile("images", "eyecatch.png", "image/png", renderPng(400, 200)));
        PostPublishCommand command = new PostPublishCommand(
                "main", "My Article", "my-article", "draft", List.of(), List.of(), null, "本文", images, null,
                List.of("assets/eyecatch.png"), null);

        service.publish(command);

        BufferedImage uploaded = ImageIO.read(new ByteArrayInputStream(bytesCaptor.getValue()));
        assertEquals(400, uploaded.getWidth());
        assertEquals(200, uploaded.getHeight());
    }

    private byte[] renderPng(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private String sha256Hex(byte[] data) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        return java.util.HexFormat.of().formatHex(digest.digest(data));
    }

    @Test
    void publish_再投稿時に内容が同じ画像は再アップロードしない() throws Exception {
        String sha256 = sha256Hex(new byte[]{1});
        String uploadedImagesJson = "{\"assets/eyecatch.png\":{\"sha256\":\"" + sha256 + "\","
                        + "\"url\":\"https://example.com/wp-content/uploads/1.png\",\"mediaId\":\"11\"}}";
        when(contentServiceClient.findPost(1L, "55")).thenReturn(Optional.of(bridgePost("55", uploadedImagesJson)));
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("55", "https://example.com/?p=55", "draft"));

        List<MultipartFile> images = List.of(
                new MockMultipartFile("images", "eyecatch.png", "image/png", new byte[]{1}));
        PostPublishCommand command = new PostPublishCommand(
                "main", "My Article", "my-article", "draft", List.of(), List.of(), "55", "本文", images, null,
                List.of("assets/eyecatch.png"), null);

        service.publish(command);

        verify(cmsAdapter, org.mockito.Mockito.never()).uploadMedia(any(), any(), any(), any());
    }

    @Test
    void publish_再投稿時に内容が異なる画像は再アップロードされる() throws Exception {
        String oldSha256 = sha256Hex(new byte[]{9, 9, 9});
        String uploadedImagesJson = "{\"assets/eyecatch.png\":{\"sha256\":\"" + oldSha256 + "\","
                        + "\"url\":\"https://example.com/old.png\",\"mediaId\":\"1\"}}";
        when(contentServiceClient.findPost(1L, "55")).thenReturn(Optional.of(bridgePost("55", uploadedImagesJson)));
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("55", "https://example.com/?p=55", "draft"));
        when(cmsAdapter.uploadMedia(any(), eq("my-article-0001.png"), any(), any()))
                .thenReturn(new MediaUploadResult("22", "https://example.com/wp-content/uploads/2.png"));

        List<MultipartFile> images = List.of(
                new MockMultipartFile("images", "eyecatch.png", "image/png", new byte[]{1}));
        PostPublishCommand command = new PostPublishCommand(
                "main", "My Article", "my-article", "draft", List.of(), List.of(), "55", "本文", images, null,
                List.of("assets/eyecatch.png"), null);

        service.publish(command);

        verify(cmsAdapter).uploadMedia(eq(credentials), eq("my-article-0001.png"), any(), any());
    }

    @Test
    void publish_既存投稿がCMS側に存在しない場合は画像キャッシュを再利用せず再アップロードする() throws Exception {
        String sha256 = sha256Hex(new byte[]{1});
        String uploadedImagesJson = "{\"assets/eyecatch.png\":{\"sha256\":\"" + sha256 + "\","
                        + "\"url\":\"https://example.com/wp-content/uploads/1.png\",\"mediaId\":\"11\"}}";
        lenient().when(contentServiceClient.findPost(1L, "55"))
                .thenReturn(Optional.of(bridgePost("55", uploadedImagesJson)));
        when(cmsAdapter.postExists(credentials, "55")).thenReturn(false);
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("70", "https://example.com/?p=70", "draft"));
        when(cmsAdapter.uploadMedia(any(), eq("my-article-0001.png"), any(), any()))
                .thenReturn(new MediaUploadResult("22", "https://example.com/wp-content/uploads/2.png"));

        List<MultipartFile> images = List.of(
                new MockMultipartFile("images", "eyecatch.png", "image/png", new byte[]{1}));
        PostPublishCommand command = new PostPublishCommand(
                "main", "My Article", "my-article", "draft", List.of(), List.of(), "55", "本文", images, null,
                List.of("assets/eyecatch.png"), null);

        service.publish(command);

        verify(cmsAdapter).uploadMedia(eq(credentials), eq("my-article-0001.png"), any(), any());
        verify(cmsAdapter).createOrUpdatePost(any(), any(), eq("55"));
    }

    @Test
    void publish_ハッシュが一致してもメディアがCMS側に実在しなければ再アップロードする() throws Exception {
        String sha256 = sha256Hex(new byte[]{1});
        String uploadedImagesJson = "{\"assets/eyecatch.png\":{\"sha256\":\"" + sha256 + "\","
                        + "\"url\":\"https://example.com/wp-content/uploads/1.png\",\"mediaId\":\"11\"}}";
        when(contentServiceClient.findPost(1L, "55")).thenReturn(Optional.of(bridgePost("55", uploadedImagesJson)));
        when(cmsAdapter.mediaExists(credentials, "11")).thenReturn(false);
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("55", "https://example.com/?p=55", "draft"));
        when(cmsAdapter.uploadMedia(any(), eq("my-article-0001.png"), any(), any()))
                .thenReturn(new MediaUploadResult("22", "https://example.com/wp-content/uploads/2.png"));

        List<MultipartFile> images = List.of(
                new MockMultipartFile("images", "eyecatch.png", "image/png", new byte[]{1}));
        PostPublishCommand command = new PostPublishCommand(
                "main", "My Article", "my-article", "draft", List.of(), List.of(), "55", "本文", images, null,
                List.of("assets/eyecatch.png"), null);

        service.publish(command);

        verify(cmsAdapter).uploadMedia(eq(credentials), eq("my-article-0001.png"), any(), any());
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
        ProjectServiceClient.ProjectBridge project = new ProjectServiceClient.ProjectBridge(7L, null, null, 1L, "test");
        lenient().when(projectServiceClient.findProjectIdBySiteId(1L)).thenReturn(7L);
        lenient().when(projectServiceClient.getProject(7L)).thenReturn(project);
    }

    /** siteId=1 をテスト環境に持つ(本番は別サイト)プロジェクトを紐づける。 */
    private void bindNonProductionSite() {
        ProjectServiceClient.ProjectBridge project = new ProjectServiceClient.ProjectBridge(7L, null, 1L, 99L, "test");
        when(projectServiceClient.findProjectIdBySiteId(1L)).thenReturn(7L);
        when(projectServiceClient.getProject(7L)).thenReturn(project);
    }

    @Test
    void publish_本番サイトへの投稿ではcontent_serviceへisProductionSite_trueを渡す() {
        bindProductionSite();
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("101", "https://example.com/?p=101", "draft"));

        service.publish(command("slug", "title", List.of(), null));

        verify(contentServiceClient).renderPreImage(anyString(), eq(7L), eq(true));
    }

    @Test
    void publish_本番以外のサイトへの投稿ではcontent_serviceへisProductionSite_falseを渡す() {
        bindNonProductionSite();
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("101", "https://example.com/?p=101", "draft"));

        service.publish(command("slug", "title", List.of(), null));

        verify(contentServiceClient).renderPreImage(anyString(), eq(7L), eq(false));
    }

    @Test
    void publish_本番サイトでは公開予定日時がstatus_futureとして送られる() {
        bindProductionSite();
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("101", "https://example.com/?p=101", "future"));
        String scheduledAt = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).plusDays(1)
                .withNano(0).toString();

        service.publish(scheduledCommand(scheduledAt));

        org.mockito.ArgumentCaptor<PostContent> captor =
                org.mockito.ArgumentCaptor.forClass(PostContent.class);
        verify(cmsAdapter).createOrUpdatePost(any(), captor.capture(), any());
        assertEquals("future", captor.getValue().status());
        assertEquals(java.time.OffsetDateTime.parse(scheduledAt).toInstant(),
                captor.getValue().publishScheduledAt());
    }

    @Test
    void publish_本番以外のサイトでは公開予定日時を無視する() {
        bindNonProductionSite();
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("101", "https://example.com/?p=101", "draft"));
        String scheduledAt = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).plusDays(1).toString();

        service.publish(scheduledCommand(scheduledAt));

        org.mockito.ArgumentCaptor<PostContent> captor =
                org.mockito.ArgumentCaptor.forClass(PostContent.class);
        verify(cmsAdapter).createOrUpdatePost(any(), captor.capture(), any());
        assertEquals("draft", captor.getValue().status());
        assertNull(captor.getValue().publishScheduledAt());
    }

    @Test
    void publish_本番サイトで過去の公開予定日時かつstatus_publishの場合は無視して即時公開する() {
        bindProductionSite();
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("101", "https://example.com/?p=101", "publish"));
        String past = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).minusDays(1).toString();

        service.publish(scheduledCommand(past, "publish"));

        org.mockito.ArgumentCaptor<PostContent> captor =
                org.mockito.ArgumentCaptor.forClass(PostContent.class);
        verify(cmsAdapter).createOrUpdatePost(any(), captor.capture(), any());
        assertEquals("publish", captor.getValue().status());
        assertNull(captor.getValue().publishScheduledAt());
    }

    @Test
    void publish_本番サイトで過去の公開予定日時かつstatus_publish以外の場合は無視して非公開のまま投稿する() {
        bindProductionSite();
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("101", "https://example.com/?p=101", "draft"));
        String past = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).minusDays(1).toString();

        service.publish(scheduledCommand(past, "draft"));

        org.mockito.ArgumentCaptor<PostContent> captor =
                org.mockito.ArgumentCaptor.forClass(PostContent.class);
        verify(cmsAdapter).createOrUpdatePost(any(), captor.capture(), any());
        assertEquals("draft", captor.getValue().status());
        assertNull(captor.getValue().publishScheduledAt());
    }

    @Test
    void publish_本番以外のサイトでは過去の公開予定日時も無視する() {
        bindNonProductionSite();
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("101", "https://example.com/?p=101", "draft"));
        String past = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).minusDays(1).toString();

        service.publish(scheduledCommand(past));

        org.mockito.ArgumentCaptor<PostContent> captor =
                org.mockito.ArgumentCaptor.forClass(PostContent.class);
        verify(cmsAdapter).createOrUpdatePost(any(), captor.capture(), any());
        assertEquals("draft", captor.getValue().status());
        assertNull(captor.getValue().publishScheduledAt());
    }

    @Test
    void publish_ISO8601以外の公開予定日時は拒否する() {
        bindProductionSite();

        IllegalArgumentException e = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> service.publish(scheduledCommand("2026/12/25 09:00")));
        assertTrue(e.getMessage().contains("ISO 8601"));
    }

    @Test
    void publish_公開予定日時が空文字の場合は通常投稿として扱う() {
        bindProductionSite();
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("101", "https://example.com/?p=101", "draft"));

        service.publish(scheduledCommand("  "));

        org.mockito.ArgumentCaptor<PostContent> captor =
                org.mockito.ArgumentCaptor.forClass(PostContent.class);
        verify(cmsAdapter).createOrUpdatePost(any(), captor.capture(), any());
        assertEquals("draft", captor.getValue().status());
        assertNull(captor.getValue().publishScheduledAt());
    }

    @Test
    void publish_rechartsタグが不正な場合は投稿を拒否する() {
        when(contentServiceClient.renderPreImage(anyString(), any(), anyBoolean()))
                .thenThrow(new InvalidRechartsTagException("type属性は必須です"));

        InvalidRechartsTagException e = org.junit.jupiter.api.Assertions.assertThrows(
                InvalidRechartsTagException.class,
                () -> service.publish(command("my-article", "My Article", List.of(), null)));
        assertTrue(e.getMessage().contains("type属性"));
        verify(cmsAdapter, org.mockito.Mockito.never()).createOrUpdatePost(any(), any(), any());
    }

    @Test
    void publish_plantumlタグをレンダリングしCMSへアップロードする() {
        when(plantUmlTagRenderService.render(eq(credentials), anyString(), anyMap()))
                .thenReturn(new DiagramEmbedResult("![diagram](https://example.com/plantuml-tag-1.png)", Map.of()));
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("101", "https://example.com/?p=101", "draft"));

        service.publish(command("my-article", "My Article", List.of(), null));

        verify(plantUmlTagRenderService).render(eq(credentials), anyString(), anyMap());
    }

    @Test
    void publish_plantumlタグが不正な場合は投稿を拒否する() {
        when(plantUmlTagRenderService.render(eq(credentials), anyString(), anyMap()))
                .thenThrow(new InvalidPlantUmlTagException("PlantUML図のレンダリングに失敗しました"));

        InvalidPlantUmlTagException e = org.junit.jupiter.api.Assertions.assertThrows(
                InvalidPlantUmlTagException.class,
                () -> service.publish(command("my-article", "My Article", List.of(), null)));
        assertTrue(e.getMessage().contains("PlantUML"));
        verify(cmsAdapter, org.mockito.Mockito.never()).createOrUpdatePost(any(), any(), any());
    }

    @Test
    void publish_前回投稿時のPlantUMLキャッシュはtagRenderServiceとembedServiceへ引き継がれる() {
        String uploadedImagesJson = "{\"plantuml:tag-hash\":{\"sha256\":\"tag-hash\","
                        + "\"url\":\"https://example.com/plantuml-tag-1.png\",\"mediaId\":\"9\"}}";
        when(contentServiceClient.findPost(1L, "55")).thenReturn(Optional.of(bridgePost("55", uploadedImagesJson)));
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("55", "https://example.com/?p=55", "draft"));

        PostPublishCommand command = new PostPublishCommand(
                "main", "My Article", "my-article", "draft", List.of(), List.of(), "55", "本文", List.of(), null,
                List.of(), null);
        service.publish(command);

        ArgumentCaptor<Map> tagPriorUploadsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(plantUmlTagRenderService).render(eq(credentials), anyString(), tagPriorUploadsCaptor.capture());
        assertTrue(tagPriorUploadsCaptor.getValue().containsKey("plantuml:tag-hash"));

        ArgumentCaptor<Map> embedPriorUploadsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(plantUmlEmbedService).embedDiagrams(eq(credentials), anyString(), embedPriorUploadsCaptor.capture());
        assertTrue(embedPriorUploadsCaptor.getValue().containsKey("plantuml:tag-hash"));
    }

    @Test
    void publish_PlantUMLダイアグラムのアップロード結果がcontent_service反映時のキャッシュに含まれる() {
        when(plantUmlEmbedService.embedDiagrams(any(), anyString(), anyMap()))
                .thenReturn(new DiagramEmbedResult("本文",
                        Map.of("plantuml:diagram-hash", new UploadedImageInfo(
                                "diagram-hash", "https://example.com/plantuml-1.png", "12"))));
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("200", "https://example.com/?p=200", "draft"));

        service.publish(command("my-article", "My Article", List.of(), null));

        ArgumentCaptor<String> uploadedImagesJsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(contentServiceClient).upsertPost(
                any(), eq("200"), any(), any(), uploadedImagesJsonCaptor.capture(), any(), any());
        assertTrue(uploadedImagesJsonCaptor.getValue().contains("plantuml:diagram-hash"));
    }

    @Test
    void publish_categoriesがcontent_serviceへの反映呼び出しに含まれる() {
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("201", "https://example.com/?p=201", "draft"));
        PostPublishCommand command = new PostPublishCommand(
                "main", "My Article", "my-article", "draft", List.of("技術", "お知らせ"), List.of(), null, "本文",
                List.of(), null, List.of(), null);

        service.publish(command);

        ArgumentCaptor<String> categoriesCaptor = ArgumentCaptor.forClass(String.class);
        verify(contentServiceClient).upsertPost(
                any(), eq("201"), any(), any(), any(), categoriesCaptor.capture(), any());
        assertTrue(categoriesCaptor.getValue().contains("技術"));
        assertTrue(categoriesCaptor.getValue().contains("お知らせ"));
    }

    @Test
    void publish_publishScheduledAtがcontent_serviceへの反映呼び出しに含まれる() {
        bindProductionSite();
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("202", "https://example.com/?p=202", "future"));
        java.time.OffsetDateTime scheduledAt = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).plusDays(1)
                .withNano(0);

        service.publish(scheduledCommand(scheduledAt.toString()));

        ArgumentCaptor<java.time.LocalDateTime> scheduledAtCaptor =
                ArgumentCaptor.forClass(java.time.LocalDateTime.class);
        verify(contentServiceClient).upsertPost(
                any(), eq("202"), any(), any(), any(), any(), scheduledAtCaptor.capture());
        assertEquals(scheduledAt.toInstant(), scheduledAtCaptor.getValue().toInstant(java.time.ZoneOffset.UTC));
    }

    @Test
    void publish_プロジェクトメンバーでもadminでもなければCMSへ触れずに拒否する() {
        // issue #830: WordPressへの公開が「認証済みなら誰でも」通っていた。CMSへの副作用
        // (画像アップロード・投稿作成)が始まる前に弾まれることを確かめる。
        lenient().when(projectServiceClient.findProjectIdBySiteId(1L)).thenReturn(7L);
        doThrow(new ForbiddenException("この操作にはプロジェクトメンバーまたはadmin権限が必要です"))
                .when(adminAuthorizationService).requireProjectMemberOrAdminForSite(7L);

        assertThrows(ForbiddenException.class,
                () -> service.publish(command("my-article", "My Article", List.of(), null)));

        verify(cmsAdapter, never()).createOrUpdatePost(any(), any(), any());
        verify(contentServiceClient, never()).renderPreImage(anyString(), any(), anyBoolean());
        verify(domainEventPublisher, never())
                .publishPostPublished(any(), any(), anyString(), anyString(), anyString());
    }

    // ---- issue #1431: WordPress側のスラッグ照会 ----

    private PostPublishCommand commandWithWpPostId(String slug, String wpPostId) {
        return new PostPublishCommand(
                "main", "My Article", slug, "draft", List.of(), List.of(), wpPostId, "本文", List.of(), null,
                null, null);
    }

    @Test
    void publish_wpPostIdが無くWordPressに同じスラッグの投稿が1件あればその投稿を更新しpostsへupsertする() {
        when(cmsAdapter.findPostIdsBySlug(credentials, "my-article")).thenReturn(List.of("55"));
        when(cmsAdapter.createOrUpdatePost(any(), any(), eq("55")))
                .thenReturn(new PostResult("55", "https://example.com/?p=55", "draft"));

        PostPublishResponse response = service.publish(commandWithWpPostId("my-article", null));

        assertEquals("55", response.wpPostId());
        verify(cmsAdapter).createOrUpdatePost(any(), any(), eq("55"));
        verify(contentServiceClient).upsertPost(
                eq(1L), eq("55"), eq("my-article"), eq("draft"), any(), any(), any());
    }

    @Test
    void publish_wpPostIdが空文字でも未指定と同じく照会する() {
        when(cmsAdapter.findPostIdsBySlug(credentials, "my-article")).thenReturn(List.of("55"));
        when(cmsAdapter.createOrUpdatePost(any(), any(), eq("55")))
                .thenReturn(new PostResult("55", "https://example.com/?p=55", "draft"));

        service.publish(commandWithWpPostId("my-article", " "));

        verify(cmsAdapter).createOrUpdatePost(any(), any(), eq("55"));
    }

    @Test
    void publish_WordPressに同じスラッグの投稿が無ければ新規作成する() {
        when(cmsAdapter.findPostIdsBySlug(credentials, "my-article")).thenReturn(List.of());
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("101", "https://example.com/?p=101", "draft"));

        PostPublishResponse response = service.publish(commandWithWpPostId("my-article", null));

        assertEquals("101", response.wpPostId());
        verify(cmsAdapter).createOrUpdatePost(any(), any(), org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void publish_slug未指定なら照会せず新規作成する() {
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("101", "https://example.com/?p=101", "draft"));

        service.publish(commandWithWpPostId(null, null));

        verify(cmsAdapter, never()).findPostIdsBySlug(any(), any());
        verify(cmsAdapter).createOrUpdatePost(any(), any(), org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void publish_slugが空白なら照会せず新規作成する() {
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("101", "https://example.com/?p=101", "draft"));

        service.publish(commandWithWpPostId("  ", null));

        verify(cmsAdapter, never()).findPostIdsBySlug(any(), any());
    }

    @Test
    void publish_wpPostIdが指定されていれば照会しない() {
        when(cmsAdapter.createOrUpdatePost(any(), any(), eq("55")))
                .thenReturn(new PostResult("55", "https://example.com/?p=55", "draft"));

        service.publish(commandWithWpPostId("my-article", "55"));

        verify(cmsAdapter, never()).findPostIdsBySlug(any(), any());
        verify(cmsAdapter).createOrUpdatePost(any(), any(), eq("55"));
    }

    @Test
    void publish_同じスラッグの投稿が複数あれば候補IDを示して中止し作成も更新もしない() {
        when(cmsAdapter.findPostIdsBySlug(credentials, "my-article")).thenReturn(List.of("55", "56"));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service.publish(commandWithWpPostId("my-article", null)));

        assertTrue(thrown.getMessage().contains("55"));
        assertTrue(thrown.getMessage().contains("56"));
        verify(cmsAdapter, never()).createOrUpdatePost(any(), any(), any());
        verify(contentServiceClient, never()).upsertPost(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void publish_照会に失敗したら新規作成へ進まず中止する() {
        when(cmsAdapter.findPostIdsBySlug(credentials, "my-article"))
                .thenThrow(new RuntimeException("ssh timeout"));

        assertThrows(RuntimeException.class, () -> service.publish(commandWithWpPostId("my-article", null)));

        verify(cmsAdapter, never()).createOrUpdatePost(any(), any(), any());
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
    void publish_ローカルに記録が無くてもWordPress側に同一sha256のメディアがあれば再アップロードせず既存URLを使う() throws Exception {
        String sha = sha256Hex(new byte[]{1});
        when(cmsAdapter.findMediaBySha256(eq(credentials), any()))
                .thenReturn(Map.of(sha, new MediaUploadResult("88", "https://example.com/wp-content/uploads/existing.png")));
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("101", "https://example.com/?p=101", "draft"));
        when(contentServiceClient.renderPreImage(anyString(), any(), anyBoolean()))
                .thenReturn("![a](assets/eyecatch.png)");

        service.publish(imageCommand(null, List.of(new byte[]{1}), List.of("assets/eyecatch.png")));

        verify(cmsAdapter, never()).uploadMedia(any(), any(), any(), any());
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(contentServiceClient).finalizeHtml(html.capture(), any());
        assertTrue(html.getValue().contains("https://example.com/wp-content/uploads/existing.png"));
    }

    @Test
    void publish_アイキャッチに指定した画像もWordPress側の既存メディアを再利用しfeaturedMediaIdになる() throws Exception {
        String sha = sha256Hex(new byte[]{1});
        when(cmsAdapter.findMediaBySha256(eq(credentials), any()))
                .thenReturn(Map.of(sha, new MediaUploadResult("88", "https://example.com/wp-content/uploads/existing.png")));
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("101", "https://example.com/?p=101", "draft"));

        service.publish(imageCommand("assets/eyecatch.png", List.of(new byte[]{1}), List.of("assets/eyecatch.png")));

        ArgumentCaptor<PostContent> content = ArgumentCaptor.forClass(PostContent.class);
        verify(cmsAdapter).createOrUpdatePost(any(), content.capture(), any());
        assertEquals("88", content.getValue().featuredMediaId());
        verify(cmsAdapter, never()).uploadMedia(any(), any(), any(), any());
    }

    @Test
    void publish_WordPress側に同一sha256が無ければ新規アップロードする() {
        when(cmsAdapter.findMediaBySha256(eq(credentials), any())).thenReturn(Map.of());
        when(cmsAdapter.uploadMedia(any(), eq("my-article-0001.png"), any(), any()))
                .thenReturn(new MediaUploadResult("22", "https://example.com/wp-content/uploads/2.png"));
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("101", "https://example.com/?p=101", "draft"));

        service.publish(imageCommand(null, List.of(new byte[]{1}), List.of("assets/eyecatch.png")));

        verify(cmsAdapter).uploadMedia(eq(credentials), eq("my-article-0001.png"), any(), any());
    }

    @Test
    void publish_照会は画像の枚数によらず1回で全画像のsha256をまとめて渡す() throws Exception {
        when(cmsAdapter.findMediaBySha256(eq(credentials), any())).thenReturn(Map.of());
        when(cmsAdapter.uploadMedia(any(), any(), any(), any()))
                .thenReturn(new MediaUploadResult("22", "https://example.com/wp-content/uploads/2.png"));
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("101", "https://example.com/?p=101", "draft"));

        service.publish(imageCommand(null, List.of(new byte[]{1}, new byte[]{2}, new byte[]{3}),
                List.of("a.png", "b.png", "c.png")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<String>> hashes = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(cmsAdapter, org.mockito.Mockito.times(1)).findMediaBySha256(eq(credentials), hashes.capture());
        assertEquals(java.util.Set.of(sha256Hex(new byte[]{1}), sha256Hex(new byte[]{2}), sha256Hex(new byte[]{3})),
                new java.util.HashSet<>(hashes.getValue()));
    }

    @Test
    void publish_ローカルのキャッシュで再利用できる画像だけなら照会しない() throws Exception {
        String sha = sha256Hex(new byte[]{1});
        String json = "{\"assets/eyecatch.png\":{\"sha256\":\"" + sha + "\","
                + "\"url\":\"https://example.com/wp-content/uploads/1.png\",\"mediaId\":\"11\"}}";
        when(contentServiceClient.findPost(1L, "55")).thenReturn(Optional.of(bridgePost("55", json)));
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("55", "https://example.com/?p=55", "draft"));
        PostPublishCommand command = new PostPublishCommand(
                "main", "My Article", "my-article", "draft", List.of(), List.of(), "55", "本文",
                List.of(new MockMultipartFile("images", "e.png", "image/png", new byte[]{1})), null,
                List.of("assets/eyecatch.png"), null);

        service.publish(command);

        verify(cmsAdapter, never()).findMediaBySha256(any(), any());
        verify(cmsAdapter, never()).uploadMedia(any(), any(), any(), any());
    }

    @Test
    void publish_WordPress側で再利用したメディアもuploadedImagesJsonに記録する() throws Exception {
        String sha = sha256Hex(new byte[]{1});
        when(cmsAdapter.findMediaBySha256(eq(credentials), any()))
                .thenReturn(Map.of(sha, new MediaUploadResult("88", "https://example.com/wp-content/uploads/existing.png")));
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("101", "https://example.com/?p=101", "draft"));

        service.publish(imageCommand(null, List.of(new byte[]{1}), List.of("assets/eyecatch.png")));

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(contentServiceClient).upsertPost(eq(1L), eq("101"), any(), any(), json.capture(), any(), any());
        assertTrue(json.getValue().contains("\"mediaId\":\"88\""));
        assertTrue(json.getValue().contains(sha));
    }

    @Test
    void publish_照会に失敗しても従来どおり新規アップロードして投稿を続行する() {
        when(cmsAdapter.findMediaBySha256(eq(credentials), any())).thenThrow(new RuntimeException("ssh timeout"));
        when(cmsAdapter.uploadMedia(any(), eq("my-article-0001.png"), any(), any()))
                .thenReturn(new MediaUploadResult("22", "https://example.com/wp-content/uploads/2.png"));
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("101", "https://example.com/?p=101", "draft"));

        PostPublishResponse response =
                service.publish(imageCommand(null, List.of(new byte[]{1}), List.of("assets/eyecatch.png")));

        assertEquals("101", response.wpPostId());
        verify(cmsAdapter).uploadMedia(eq(credentials), eq("my-article-0001.png"), any(), any());
    }

    @Test
    void publish_内容が異なる画像は同じ参照名でも既存メディアを再利用せず新規アップロードする() throws Exception {
        // WordPress側にあるのは別内容(sha256が異なる)のメディアだけ。
        when(cmsAdapter.findMediaBySha256(eq(credentials), any()))
                .thenReturn(Map.of(sha256Hex(new byte[]{9}),
                        new MediaUploadResult("88", "https://example.com/wp-content/uploads/other.png")));
        when(cmsAdapter.uploadMedia(any(), eq("my-article-0001.png"), any(), any()))
                .thenReturn(new MediaUploadResult("22", "https://example.com/wp-content/uploads/2.png"));
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("101", "https://example.com/?p=101", "draft"));

        service.publish(imageCommand("assets/eyecatch.png", List.of(new byte[]{1}), List.of("assets/eyecatch.png")));

        ArgumentCaptor<PostContent> content = ArgumentCaptor.forClass(PostContent.class);
        verify(cmsAdapter).createOrUpdatePost(any(), content.capture(), any());
        assertEquals("22", content.getValue().featuredMediaId());
    }

    @Test
    void publish_照会結果がnullでも新規アップロードして続行する() {
        when(cmsAdapter.findMediaBySha256(eq(credentials), any())).thenReturn(null);
        when(cmsAdapter.uploadMedia(any(), eq("my-article-0001.png"), any(), any()))
                .thenReturn(new MediaUploadResult("22", "https://example.com/wp-content/uploads/2.png"));
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("101", "https://example.com/?p=101", "draft"));

        service.publish(imageCommand(null, List.of(new byte[]{1}), List.of("assets/eyecatch.png")));

        verify(cmsAdapter).uploadMedia(eq(credentials), eq("my-article-0001.png"), any(), any());
    }

    // ---- issue #1557: プラグインが使えないサイトへの投稿は拒否する ----

    @Test
    void publish_letsblogプラグインが使えないサイトは拒否しCMSへ何も書き込まない() {
        doThrow(new com.letsblog.publishing.cms.LetsblogPluginUnavailableException(
                com.letsblog.publishing.cms.LetsblogPluginStatus.notInstalled()))
                .when(cmsAdapter).requireLetsblogPlugin(any());

        com.letsblog.publishing.cms.LetsblogPluginUnavailableException e = assertThrows(
                com.letsblog.publishing.cms.LetsblogPluginUnavailableException.class,
                () -> service.publish(command("slug", "title", List.of(), null)));

        assertTrue(e.getMessage().contains("再導入"));
        verify(cmsAdapter, never()).createOrUpdatePost(any(), any(), any());
        verify(cmsAdapter, never()).uploadMedia(any(), any(), any(), any());
        verify(cmsAdapter, never()).resolveCategories(any(), any());
        verify(domainEventPublisher, never()).publishPostPublished(any(), any(), any(), any(), any());
    }
}
