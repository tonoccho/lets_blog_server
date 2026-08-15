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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
        lenient().when(amazonTagRenderService.render(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(rechartsTagRenderService.render(anyString())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(plantUmlTagRenderService.render(any(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        lenient().when(plantUmlEmbedService.embedDiagrams(any(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        lenient().when(markdownRenderer.render(anyString())).thenAnswer(inv -> "<p>" + inv.getArgument(0) + "</p>");
        lenient().when(tocStyleRenderService.applyHtmlTemplate(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(renderedContentWrapperService.wrap(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(cmsAdapter.resolveCategories(any(), any())).thenReturn(List.of());
        lenient().when(cmsAdapter.resolveTags(any(), any())).thenReturn(List.of());
        lenient().when(postRepository.findBySiteIdAndWpPostId(any(), any())).thenReturn(Optional.empty());
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
        when(cmsAdapter.uploadMedia(any(), eq("my-article-0001.png"), any(), bytesCaptor.capture()))
                .thenReturn(new MediaUploadResult("1", "https://example.com/wp-content/uploads/1.png"));
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
        when(cmsAdapter.uploadMedia(any(), eq("my-article-0001.png"), any(), bytesCaptor.capture()))
                .thenReturn(new MediaUploadResult("1", "https://example.com/wp-content/uploads/1.png"));
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
        when(plantUmlTagRenderService.render(eq(credentials), anyString()))
                .thenReturn("![diagram](https://example.com/plantuml-tag-1.png)");
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("101", "https://example.com/?p=101", "draft"));

        service.publish(command("my-article", "My Article", List.of(), null));

        verify(plantUmlTagRenderService).render(eq(credentials), anyString());
    }

    @Test
    void publish_plantumlタグが不正な場合は投稿を拒否する() {
        when(plantUmlTagRenderService.render(eq(credentials), anyString()))
                .thenThrow(new InvalidPlantUmlTagException("PlantUML図のレンダリングに失敗しました"));

        InvalidPlantUmlTagException e = org.junit.jupiter.api.Assertions.assertThrows(
                InvalidPlantUmlTagException.class,
                () -> service.publish(command("my-article", "My Article", List.of(), null)));
        assertTrue(e.getMessage().contains("PlantUML"));
        verify(cmsAdapter, org.mockito.Mockito.never()).createOrUpdatePost(any(), any(), any());
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

        verify(bufferNotificationService).notifyAsync(any(), eq(1L), eq("My Article"), eq("https://example.com/?p=101"));
    }

    @Test
    void publish_本番サイトへの予約投稿でもBuffer通知が呼ばれる() {
        bindProductionSite();
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("101", "https://example.com/?p=101", "future"));
        String scheduledAt = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).plusDays(1)
                .withNano(0).toString();

        service.publish(scheduledCommand(scheduledAt));

        verify(bufferNotificationService).notifyAsync(any(), eq(1L), eq("My Article"), eq("https://example.com/?p=101"));
    }

    @Test
    void publish_下書きではBuffer通知が呼ばれない() {
        bindProductionSite();
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("101", "https://example.com/?p=101", "draft"));

        service.publish(commandWithStatusAndNotify("draft", null));

        verify(bufferNotificationService, org.mockito.Mockito.never()).notifyAsync(any(), any(), any(), any());
    }

    @Test
    void publish_本番以外のサイトではBuffer通知が呼ばれない() {
        bindNonProductionSite();
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("101", "https://example.com/?p=101", "publish"));

        service.publish(commandWithStatusAndNotify("publish", null));

        verify(bufferNotificationService, org.mockito.Mockito.never()).notifyAsync(any(), any(), any(), any());
    }

    @Test
    void publish_notifySnsがfalseならBuffer通知が呼ばれない() {
        bindProductionSite();
        when(cmsAdapter.createOrUpdatePost(any(), any(), any()))
                .thenReturn(new PostResult("101", "https://example.com/?p=101", "publish"));

        service.publish(commandWithStatusAndNotify("publish", false));

        verify(bufferNotificationService, org.mockito.Mockito.never()).notifyAsync(any(), any(), any(), any());
    }
}
