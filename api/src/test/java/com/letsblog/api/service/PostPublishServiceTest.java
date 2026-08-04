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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    private ProjectService projectService;
    @Mock
    private CmsAdapter cmsAdapter;

    private PostPublishService service;

    private final CmsCredentials.WordPressCredentials credentials =
            new CmsCredentials.WordPressCredentials("https://example.com", "admin", "secret");

    @BeforeEach
    void setUp() {
        service = new PostPublishService(siteService, cmsAdapterFactory, markdownRenderer, postRepository,
                plantUmlEmbedService, customTagRenderService, projectService);

        Site site = new Site();
        site.setId(1L);
        site.setSiteKey("main");
        site.setCmsType(CmsType.WORDPRESS);

        lenient().when(siteService.getBySiteKey("main")).thenReturn(site);
        lenient().when(siteService.getCredentials("main")).thenReturn(credentials);
        lenient().when(cmsAdapterFactory.resolve(CmsType.WORDPRESS)).thenReturn(cmsAdapter);
        lenient().when(projectService.findProjectIdBySiteId(1L)).thenReturn(null);
        lenient().when(customTagRenderService.render(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(plantUmlEmbedService.embedDiagrams(any(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        lenient().when(markdownRenderer.render(anyString())).thenAnswer(inv -> "<p>" + inv.getArgument(0) + "</p>");
        lenient().when(cmsAdapter.resolveCategories(any(), any())).thenReturn(List.of());
        lenient().when(cmsAdapter.resolveTags(any(), any())).thenReturn(List.of());
        lenient().when(postRepository.findBySiteIdAndWpPostId(any(), any())).thenReturn(Optional.empty());
    }

    private PostPublishCommand command(String slug, String title, List<MultipartFile> images, String featuredImageFilename) {
        return new PostPublishCommand(
                "main", title, slug, "draft", List.of(), List.of(), null, "本文", images, featuredImageFilename);
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
}
