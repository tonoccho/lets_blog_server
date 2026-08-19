package com.letsblog.api.service;

import com.letsblog.api.cms.CmsAdapter;
import com.letsblog.api.cms.CmsAdapterFactory;
import com.letsblog.api.cms.CmsCredentials;
import com.letsblog.api.cms.CmsType;
import com.letsblog.api.cms.MediaUploadResult;
import com.letsblog.api.cms.PostContent;
import com.letsblog.api.cms.PostResult;
import com.letsblog.api.domain.Site;
import com.letsblog.api.dto.PostPublishCommand;
import com.letsblog.api.dto.PostPublishResponse;
import com.letsblog.api.markdown.MarkdownRenderer;
import com.letsblog.api.repository.PostRepository;
import com.letsblog.api.repository.UserRepository;
import com.letsblog.api.repository.UserSiteAuthorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PostPublishServiceの回帰テスト。画像ファイル名リネーム({slug}-{4桁連番}.{拡張子})と
 * featured_image指定時のfeatured_media解決を中心に検証する。
 */
@ExtendWith(MockitoExtension.class)
class PostPublishServiceTest {

    @Mock
    private SiteService siteService;
    @Mock
    private CmsAdapterFactory cmsAdapterFactory;
    @Mock
    private MarkdownRenderer markdownRenderer;
    @Mock
    private PostRepository postRepository;
    @Mock
    private PlantUmlEmbedService plantUmlEmbedService;
    @Mock
    private CustomTagRenderService customTagRenderService;
    @Mock
    private BlogCardTagRenderService blogCardTagRenderService;
    @Mock
    private AmazonTagRenderService amazonTagRenderService;
    @Mock
    private RechartsTagRenderService rechartsTagRenderService;
    @Mock
    private PlantUmlTagRenderService plantUmlTagRenderService;
    @Mock
    private TocStyleRenderService tocStyleRenderService;
    @Mock
    private RenderedContentWrapperService renderedContentWrapperService;
    @Mock
    private ProjectService projectService;
    @Mock
    private CurrentActorService currentActorService;
    @Mock
    private UserRepository userRepository;
    @Mock
    private UserSiteAuthorRepository userSiteAuthorRepository;
    @Mock
    private CmsAdapter cmsAdapter;
    @Mock
    private BufferNotificationService bufferNotificationService;

    private PostPublishService service;

    private final CmsCredentials.WordPressCredentials credentials =
            new CmsCredentials.WordPressCredentials("https://example.com", "admin", "secret");

    @BeforeEach
    void setUp() {
        service = new PostPublishService(siteService, cmsAdapterFactory, markdownRenderer, postRepository,
                plantUmlEmbedService, customTagRenderService, blogCardTagRenderService, amazonTagRenderService,
                rechartsTagRenderService, plantUmlTagRenderService, tocStyleRenderService, renderedContentWrapperService,
                projectService, currentActorService, userRepository, userSiteAuthorRepository,
                new com.fasterxml.jackson.databind.ObjectMapper(), new ImageResizeService(), bufferNotificationService);

        Site site = new Site();
        site.setId(1L);
        site.setSiteKey("main");
        site.setCmsType(CmsType.WORDPRESS);

        lenient().when(siteService.getBySiteKey("main")).thenReturn(site);
        lenient().when(siteService.getCredentials("main")).thenReturn(credentials);
        lenient().when(cmsAdapterFactory.resolve(CmsType.WORDPRESS)).thenReturn(cmsAdapter);
        lenient().when(projectService.findProjectIdBySiteId(1L)).thenReturn(null);
        lenient().when(customTagRenderService.render(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(blogCardTagRenderService.render(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(amazonTagRenderService.render(anyString(), any(), anyBoolean()))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(rechartsTagRenderService.render(anyString())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(plantUmlTagRenderService.render(any(), anyString(), anyMap()))
                .thenAnswer(inv -> new DiagramEmbedResult(inv.getArgument(1), inv.getArgument(2)));
        lenient().when(plantUmlEmbedService.embedDiagrams(any(), anyString(), anyMap()))
                .thenAnswer(inv -> new DiagramEmbedResult(inv.getArgument(1), inv.getArgument(2)));
        lenient().when(markdownRenderer.render(anyString())).thenAnswer(inv -> "<p>" + inv.getArgument(0) + "</p>");
        lenient().when(tocStyleRenderService.applyHtmlTemplate(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(renderedContentWrapperService.wrap(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(cmsAdapter.resolveCategories(any(), any())).thenReturn(List.of());
        lenient().when(cmsAdapter.resolveTags(any(), any())).thenReturn(List.of());
        lenient().when(postRepository.findBySiteIdAndWpPostId(any(), any())).thenReturn(Optional.empty());
        lenient().when(cmsAdapter.postExists(any(), any())).thenReturn(true);
        lenient().when(cmsAdapter.mediaExists(any(), any())).thenReturn(true);
        lenient().when(currentActorService.getCurrentActorId()).thenReturn(null);
        lenient().when(userSiteAuthorRepository.findByUserIdAndSiteId(any(), any())).thenReturn(Optional.empty());
    }

    private PostPublishCommand command(String slug, String title, List<MultipartFile> images, String featuredImageFilename) {
        return command(slug, title, images, featuredImageFilename, null);
    }

    private PostPublishCommand command(String slug, String title, List<MultipartFile> images,
            String featuredImageFilename, List<String> imageReferences) {
        return new PostPublishCommand(
                "main", title, slug, "draft", List.of(), List.of(), null, "本文", images, featuredImageFilename,
                imageReferences, null, null);
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
    void publish_最終HTMLはRenderedContentWrapperServiceでラップされてPostContentへ渡される() {
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("101", "https://example.com/?p=101", "draft"));
        when(tocStyleRenderService.applyHtmlTemplate(anyString(), any()))
                .thenAnswer(inv -> "[template]" + inv.getArgument(0));
        when(renderedContentWrapperService.wrap(anyString(), any()))
                .thenReturn("<div class=\"lets-blog-rendered\">wrapped</div>");

        service.publish(command("my-article", "My Article", List.of(), null));

        verify(renderedContentWrapperService).wrap("[template]<p>本文</p>", null);
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
        when(customTagRenderService.render(anyString(), any())).thenReturn("![alt](eyecatch.png)");

        List<MultipartFile> images = List.of(
                new MockMultipartFile("images", "eyecatch.png", "image/png", new byte[]{1}));

        service.publish(command("my-article", "My Article", images, null));

        verify(markdownRenderer).render("![alt](https://example.com/wp-content/uploads/1.png)");
    }

    @Test
    void publish_assetsサブフォルダ参照はimageReferencesを使い二重結合されずに置換される() {
        // マルチパートのoriginalFilenameがコンテナ/サーバー側で"assets/eyecatch.png"から
        // "eyecatch.png"へパス部分を失って渡ってきても(実運用で確認された不具合の再現)、
        // imageReferencesで明示された"assets/eyecatch.png"を優先して置換に使う。
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("101", "https://example.com/?p=101", "draft"));
        when(cmsAdapter.uploadMedia(any(), eq("my-article-0001.png"), any(), any()))
                .thenReturn(new MediaUploadResult("11", "https://example.com/wp-content/uploads/1.png"));
        when(customTagRenderService.render(anyString(), any())).thenReturn("![alt](assets/eyecatch.png)");

        List<MultipartFile> images = List.of(
                new MockMultipartFile("images", "eyecatch.png", "image/png", new byte[]{1}));

        service.publish(command("my-article", "My Article", images, "assets/eyecatch.png",
                List.of("assets/eyecatch.png")));

        verify(markdownRenderer).render("![alt](https://example.com/wp-content/uploads/1.png)");
        ArgumentCaptor<PostContent> contentCaptor = ArgumentCaptor.forClass(PostContent.class);
        verify(cmsAdapter).createOrUpdatePost(eq(credentials), contentCaptor.capture(), any());
        assertEquals("11", contentCaptor.getValue().featuredMediaId());
    }

    @Test
    void publish_発信者に対応するWordPressユーザーが見つかればauthorIdを設定する() {
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("101", "https://example.com/?p=101", "draft"));
        when(currentActorService.getCurrentActorId()).thenReturn(10L);
        com.letsblog.api.domain.User user = new com.letsblog.api.domain.User();
        user.setId(10L);
        user.setEmail("author@example.com");
        when(userRepository.findById(10L)).thenReturn(Optional.of(user));
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
        when(userSiteAuthorRepository.findByUserIdAndSiteId(10L, 1L))
                .thenReturn(Optional.of(new com.letsblog.api.domain.UserSiteAuthor(10L, 1L, "7")));

        service.publish(command("my-article", "My Article", List.of(), null));

        ArgumentCaptor<PostContent> contentCaptor = ArgumentCaptor.forClass(PostContent.class);
        verify(cmsAdapter).createOrUpdatePost(eq(credentials), contentCaptor.capture(), any());
        assertEquals("7", contentCaptor.getValue().authorId());
        verify(cmsAdapter, org.mockito.Mockito.never()).findAuthorIdByEmail(any(), any());
        verify(userRepository, org.mockito.Mockito.never()).findById(any());
    }

    @Test
    void publish_メール検索で解決できた場合はuser_site_authorsへキャッシュする() {
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("101", "https://example.com/?p=101", "draft"));
        when(currentActorService.getCurrentActorId()).thenReturn(10L);
        com.letsblog.api.domain.User user = new com.letsblog.api.domain.User();
        user.setId(10L);
        user.setEmail("author@example.com");
        when(userRepository.findById(10L)).thenReturn(Optional.of(user));
        when(cmsAdapter.findAuthorIdByEmail(credentials, "author@example.com")).thenReturn(Optional.of("7"));

        service.publish(command("my-article", "My Article", List.of(), null));

        ArgumentCaptor<com.letsblog.api.domain.UserSiteAuthor> mappingCaptor =
                ArgumentCaptor.forClass(com.letsblog.api.domain.UserSiteAuthor.class);
        verify(userSiteAuthorRepository).save(mappingCaptor.capture());
        assertEquals(10L, mappingCaptor.getValue().getUserId());
        assertEquals(1L, mappingCaptor.getValue().getSiteId());
        assertEquals("7", mappingCaptor.getValue().getCmsAuthorId());
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
        when(userRepository.findById(10L)).thenThrow(new RuntimeException("DB接続エラー"));

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
        // renderPng()は透過を持たないPNGのため、アップロード時にJPEGへ変換される(issue #468)。
        when(cmsAdapter.uploadMedia(any(), eq("my-article-0001.jpg"), any(), bytesCaptor.capture()))
                .thenReturn(new MediaUploadResult("1", "https://example.com/wp-content/uploads/1.jpg"));
        when(projectService.resolveArticleImageLongEdgePx(any())).thenReturn(100);

        List<MultipartFile> images = List.of(
                new MockMultipartFile("images", "eyecatch.png", "image/png", renderPng(400, 200)));
        PostPublishCommand command = new PostPublishCommand(
                "main", "My Article", "my-article", "draft", List.of(), List.of(), null, "本文", images, null,
                List.of("assets/eyecatch.png"), null, null);

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
        // renderPng()は透過を持たないPNGのため、アップロード時にJPEGへ変換される(issue #468)。
        when(cmsAdapter.uploadMedia(any(), eq("my-article-0001.jpg"), any(), bytesCaptor.capture()))
                .thenReturn(new MediaUploadResult("1", "https://example.com/wp-content/uploads/1.jpg"));
        when(projectService.resolveArticleImageLongEdgePx(any())).thenReturn(1300);

        List<MultipartFile> images = List.of(
                new MockMultipartFile("images", "eyecatch.png", "image/png", renderPng(400, 200)));
        PostPublishCommand command = new PostPublishCommand(
                "main", "My Article", "my-article", "draft", List.of(), List.of(), null, "本文", images, null,
                List.of("assets/eyecatch.png"), null, null);

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
        com.letsblog.api.domain.Post existingPost = new com.letsblog.api.domain.Post();
        existingPost.setSiteId(1L);
        existingPost.setWpPostId("55");
        String sha256 = sha256Hex(new byte[]{1});
        existingPost.setUploadedImagesJson(
                "{\"assets/eyecatch.png\":{\"sha256\":\"" + sha256 + "\","
                        + "\"url\":\"https://example.com/wp-content/uploads/1.png\",\"mediaId\":\"11\"}}");
        when(postRepository.findBySiteIdAndWpPostId(1L, "55")).thenReturn(Optional.of(existingPost));
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("55", "https://example.com/?p=55", "draft"));

        List<MultipartFile> images = List.of(
                new MockMultipartFile("images", "eyecatch.png", "image/png", new byte[]{1}));
        PostPublishCommand command = new PostPublishCommand(
                "main", "My Article", "my-article", "draft", List.of(), List.of(), "55", "本文", images, null,
                List.of("assets/eyecatch.png"), null, null);

        service.publish(command);

        verify(cmsAdapter, org.mockito.Mockito.never()).uploadMedia(any(), any(), any(), any());
    }

    @Test
    void publish_再投稿時に内容が異なる画像は再アップロードされる() throws Exception {
        com.letsblog.api.domain.Post existingPost = new com.letsblog.api.domain.Post();
        existingPost.setSiteId(1L);
        existingPost.setWpPostId("55");
        String oldSha256 = sha256Hex(new byte[]{9, 9, 9});
        existingPost.setUploadedImagesJson(
                "{\"assets/eyecatch.png\":{\"sha256\":\"" + oldSha256 + "\","
                        + "\"url\":\"https://example.com/old.png\",\"mediaId\":\"1\"}}");
        when(postRepository.findBySiteIdAndWpPostId(1L, "55")).thenReturn(Optional.of(existingPost));
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("55", "https://example.com/?p=55", "draft"));
        when(cmsAdapter.uploadMedia(any(), eq("my-article-0001.png"), any(), any()))
                .thenReturn(new MediaUploadResult("22", "https://example.com/wp-content/uploads/2.png"));

        List<MultipartFile> images = List.of(
                new MockMultipartFile("images", "eyecatch.png", "image/png", new byte[]{1}));
        PostPublishCommand command = new PostPublishCommand(
                "main", "My Article", "my-article", "draft", List.of(), List.of(), "55", "本文", images, null,
                List.of("assets/eyecatch.png"), null, null);

        service.publish(command);

        verify(cmsAdapter).uploadMedia(eq(credentials), eq("my-article-0001.png"), any(), any());
    }

    @Test
    void publish_既存投稿がCMS側に存在しない場合は画像キャッシュを再利用せず再アップロードする() throws Exception {
        // issue #493: CMS側で投稿(および一緒にアップロードした画像)が削除された後にwpPostIdだけが
        // ローカルDBに残っているケース。sha256が一致していても、投稿自体が実在しなければ
        // キャッシュされたURLはリンク切れの可能性が高いため再利用してはならない。
        com.letsblog.api.domain.Post existingPost = new com.letsblog.api.domain.Post();
        existingPost.setSiteId(1L);
        existingPost.setWpPostId("55");
        String sha256 = sha256Hex(new byte[]{1});
        existingPost.setUploadedImagesJson(
                "{\"assets/eyecatch.png\":{\"sha256\":\"" + sha256 + "\","
                        + "\"url\":\"https://example.com/wp-content/uploads/1.png\",\"mediaId\":\"11\"}}");
        // 「投稿が消えても画像キャッシュを取りに行かない」ことを示すため敢えて存在するかのように
        // スタブするが、postExists=falseの分岐で読み出し自体が起きないため未使用になる(意図通り)。
        lenient().when(postRepository.findBySiteIdAndWpPostId(1L, "55")).thenReturn(Optional.of(existingPost));
        when(cmsAdapter.postExists(credentials, "55")).thenReturn(false);
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("70", "https://example.com/?p=70", "draft"));
        when(cmsAdapter.uploadMedia(any(), eq("my-article-0001.png"), any(), any()))
                .thenReturn(new MediaUploadResult("22", "https://example.com/wp-content/uploads/2.png"));

        List<MultipartFile> images = List.of(
                new MockMultipartFile("images", "eyecatch.png", "image/png", new byte[]{1}));
        PostPublishCommand command = new PostPublishCommand(
                "main", "My Article", "my-article", "draft", List.of(), List.of(), "55", "本文", images, null,
                List.of("assets/eyecatch.png"), null, null);

        service.publish(command);

        verify(cmsAdapter).uploadMedia(eq(credentials), eq("my-article-0001.png"), any(), any());
        // existingPostId自体はcreateOrUpdatePostへそのまま渡す(実在確認と作成/更新へのフォールバックは
        // createOrUpdatePost実装内で個別に行うため)。
        verify(cmsAdapter).createOrUpdatePost(any(), any(), eq("55"));
    }

    @Test
    void publish_ハッシュが一致してもメディアがCMS側に実在しなければ再アップロードする() throws Exception {
        // issue #495: 投稿自体は実在するが、その画像だけがメディアライブラリから個別に削除された
        // ケース。sha256が一致していても、メディア単位の実在確認(mediaExists)がfalseなら
        // キャッシュされたURLはリンク切れのため再利用してはならない。
        com.letsblog.api.domain.Post existingPost = new com.letsblog.api.domain.Post();
        existingPost.setSiteId(1L);
        existingPost.setWpPostId("55");
        String sha256 = sha256Hex(new byte[]{1});
        existingPost.setUploadedImagesJson(
                "{\"assets/eyecatch.png\":{\"sha256\":\"" + sha256 + "\","
                        + "\"url\":\"https://example.com/wp-content/uploads/1.png\",\"mediaId\":\"11\"}}");
        when(postRepository.findBySiteIdAndWpPostId(1L, "55")).thenReturn(Optional.of(existingPost));
        when(cmsAdapter.mediaExists(credentials, "11")).thenReturn(false);
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("55", "https://example.com/?p=55", "draft"));
        when(cmsAdapter.uploadMedia(any(), eq("my-article-0001.png"), any(), any()))
                .thenReturn(new MediaUploadResult("22", "https://example.com/wp-content/uploads/2.png"));

        List<MultipartFile> images = List.of(
                new MockMultipartFile("images", "eyecatch.png", "image/png", new byte[]{1}));
        PostPublishCommand command = new PostPublishCommand(
                "main", "My Article", "my-article", "draft", List.of(), List.of(), "55", "本文", images, null,
                List.of("assets/eyecatch.png"), null, null);

        service.publish(command);

        verify(cmsAdapter).uploadMedia(eq(credentials), eq("my-article-0001.png"), any(), any());
    }

    private PostPublishCommand scheduledCommand(String publishScheduledAt) {
        return new PostPublishCommand(
                "main", "My Article", "my-article", "draft", List.of(), List.of(), null, "本文", List.of(), null,
                List.of(), publishScheduledAt, null);
    }

    /** siteId=1 を本番サイトに持つプロジェクトを紐づける。 */
    private void bindProductionSite() {
        com.letsblog.api.domain.Project project = new com.letsblog.api.domain.Project();
        project.setId(7L);
        project.setProductionSiteId(1L);
        // 形式不正・過去日時は本番判定より前に弾かれるため、この経路を通らないことがある。
        lenient().when(projectService.findProjectIdBySiteId(1L)).thenReturn(7L);
        lenient().when(projectService.getProjectEntity(7L)).thenReturn(project);
    }

    /** siteId=1 をテスト環境に持つ(本番は別サイト)プロジェクトを紐づける。 */
    private void bindNonProductionSite() {
        com.letsblog.api.domain.Project project = new com.letsblog.api.domain.Project();
        project.setId(7L);
        project.setTestSiteId(1L);
        project.setProductionSiteId(99L);
        when(projectService.findProjectIdBySiteId(1L)).thenReturn(7L);
        when(projectService.getProjectEntity(7L)).thenReturn(project);
    }

    @Test
    void publish_本番サイトへの投稿ではAmazonタグレンダリングにisProductionSite_trueを渡す() {
        bindProductionSite();
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("101", "https://example.com/?p=101", "draft"));

        service.publish(command("slug", "title", List.of(), null));

        verify(amazonTagRenderService).render(anyString(), eq(7L), eq(true));
    }

    @Test
    void publish_本番以外のサイトへの投稿ではAmazonタグレンダリングにisProductionSite_falseを渡す() {
        bindNonProductionSite();
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("101", "https://example.com/?p=101", "draft"));

        service.publish(command("slug", "title", List.of(), null));

        verify(amazonTagRenderService).render(anyString(), eq(7L), eq(false));
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
        // 予約は無視され、指定どおりのstatusで投稿される。
        assertEquals("draft", captor.getValue().status());
        assertNull(captor.getValue().publishScheduledAt());
    }

    @Test
    void publish_過去の公開予定日時は拒否する() {
        bindProductionSite();
        String past = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).minusDays(1).toString();

        IllegalArgumentException e = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> service.publish(scheduledCommand(past)));
        assertTrue(e.getMessage().contains("未来の日時"));
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
        when(rechartsTagRenderService.render(anyString()))
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
        // issue #499: PlantUMLダイアグラムの再利用判定に使うキャッシュは、通常画像と同じPostの
        // uploadedImagesJsonに保存されている。plantUmlTagRenderService→plantUmlEmbedServiceの順で
        // 呼び出す際、前段の戻り値(更新後のキャッシュ)が次段にそのまま引き継がれることを検証する。
        com.letsblog.api.domain.Post existingPost = new com.letsblog.api.domain.Post();
        existingPost.setSiteId(1L);
        existingPost.setWpPostId("55");
        existingPost.setUploadedImagesJson(
                "{\"plantuml:tag-hash\":{\"sha256\":\"tag-hash\","
                        + "\"url\":\"https://example.com/plantuml-tag-1.png\",\"mediaId\":\"9\"}}");
        when(postRepository.findBySiteIdAndWpPostId(1L, "55")).thenReturn(Optional.of(existingPost));
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("55", "https://example.com/?p=55", "draft"));

        PostPublishCommand command = new PostPublishCommand(
                "main", "My Article", "my-article", "draft", List.of(), List.of(), "55", "本文", List.of(), null,
                List.of(), null, null);
        service.publish(command);

        ArgumentCaptor<Map> tagPriorUploadsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(plantUmlTagRenderService).render(eq(credentials), anyString(), tagPriorUploadsCaptor.capture());
        assertTrue(tagPriorUploadsCaptor.getValue().containsKey("plantuml:tag-hash"));

        ArgumentCaptor<Map> embedPriorUploadsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(plantUmlEmbedService).embedDiagrams(eq(credentials), anyString(), embedPriorUploadsCaptor.capture());
        assertTrue(embedPriorUploadsCaptor.getValue().containsKey("plantuml:tag-hash"));
    }

    @Test
    void publish_PlantUMLダイアグラムのアップロード結果がPost保存時のキャッシュに含まれる() {
        // issue #499: plantUmlEmbedServiceが返した更新後キャッシュ(通常画像+ダイアグラム双方)が、
        // 次回投稿時の再利用判定のためPostのuploadedImagesJsonへ保存されることを検証する。
        when(plantUmlEmbedService.embedDiagrams(any(), anyString(), anyMap()))
                .thenReturn(new DiagramEmbedResult("本文",
                        Map.of("plantuml:diagram-hash", new UploadedImageInfo(
                                "diagram-hash", "https://example.com/plantuml-1.png", "12"))));
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("200", "https://example.com/?p=200", "draft"));

        service.publish(command("my-article", "My Article", List.of(), null));

        ArgumentCaptor<com.letsblog.api.domain.Post> postCaptor =
                ArgumentCaptor.forClass(com.letsblog.api.domain.Post.class);
        verify(postRepository).save(postCaptor.capture());
        assertTrue(postCaptor.getValue().getUploadedImagesJson().contains("plantuml:diagram-hash"));
    }

    @Test
    void publish_categoriesがローカルDBのPostへ保存される() {
        // issue #506: WordPressへ送信したカテゴリはposts.categoriesへJSON配列として保存され、
        // 再投稿時にも失われないことを検証する。
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("201", "https://example.com/?p=201", "draft"));
        PostPublishCommand command = new PostPublishCommand(
                "main", "My Article", "my-article", "draft", List.of("技術", "お知らせ"), List.of(), null, "本文",
                List.of(), null, List.of(), null, null);

        service.publish(command);

        ArgumentCaptor<com.letsblog.api.domain.Post> postCaptor =
                ArgumentCaptor.forClass(com.letsblog.api.domain.Post.class);
        verify(postRepository).save(postCaptor.capture());
        assertTrue(postCaptor.getValue().getCategories().contains("技術"));
        assertTrue(postCaptor.getValue().getCategories().contains("お知らせ"));
    }

    @Test
    void publish_publishScheduledAtがローカルDBのPostへ保存される() {
        // issue #506: 本番サイトへの予約投稿では、実際に適用された公開予定日時がposts.publish_scheduled_atへ保存される。
        bindProductionSite();
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("202", "https://example.com/?p=202", "future"));
        java.time.OffsetDateTime scheduledAt = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).plusDays(1)
                .withNano(0);

        service.publish(scheduledCommand(scheduledAt.toString()));

        ArgumentCaptor<com.letsblog.api.domain.Post> postCaptor =
                ArgumentCaptor.forClass(com.letsblog.api.domain.Post.class);
        verify(postRepository).save(postCaptor.capture());
        assertEquals(scheduledAt.toInstant(),
                postCaptor.getValue().getPublishScheduledAt().toInstant(java.time.ZoneOffset.UTC));
    }

    private PostPublishCommand commandWithStatusAndNotify(String status, Boolean notifySns) {
        return new PostPublishCommand(
                "main", "My Article", "my-article", status, List.of(), List.of(), null, "本文", List.of(), null,
                List.of(), null, notifySns);
    }

    @Test
    void publish_本番サイトへの公開ではBuffer通知が呼ばれる() {
        bindProductionSite();
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("101", "https://example.com/?p=101", "publish"));

        service.publish(commandWithStatusAndNotify("publish", null));

        verify(bufferNotificationService).notifyAsync(any(), eq(1L), eq(7L), eq("My Article"), eq("https://example.com/?p=101"));
    }

    @Test
    void publish_本番サイトへの予約投稿でもBuffer通知が呼ばれる() {
        bindProductionSite();
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("101", "https://example.com/?p=101", "future"));
        String scheduledAt = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).plusDays(1)
                .withNano(0).toString();

        service.publish(scheduledCommand(scheduledAt));

        verify(bufferNotificationService).notifyAsync(any(), eq(1L), eq(7L), eq("My Article"), eq("https://example.com/?p=101"));
    }

    @Test
    void publish_下書きではBuffer通知が呼ばれない() {
        bindProductionSite();
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("101", "https://example.com/?p=101", "draft"));

        service.publish(commandWithStatusAndNotify("draft", null));

        verify(bufferNotificationService, org.mockito.Mockito.never()).notifyAsync(any(), any(), any(), any(), any());
    }

    @Test
    void publish_本番以外のサイトではBuffer通知が呼ばれない() {
        bindNonProductionSite();
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("101", "https://example.com/?p=101", "publish"));

        service.publish(commandWithStatusAndNotify("publish", null));

        verify(bufferNotificationService, org.mockito.Mockito.never()).notifyAsync(any(), any(), any(), any(), any());
    }

    @Test
    void publish_notifySnsがfalseならBuffer通知が呼ばれない() {
        bindProductionSite();
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("101", "https://example.com/?p=101", "publish"));

        service.publish(commandWithStatusAndNotify("publish", false));

        verify(bufferNotificationService, org.mockito.Mockito.never()).notifyAsync(any(), any(), any(), any(), any());
    }
}
