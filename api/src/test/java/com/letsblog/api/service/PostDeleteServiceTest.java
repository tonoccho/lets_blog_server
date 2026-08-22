package com.letsblog.api.service;

import com.letsblog.api.cms.CmsAdapter;
import com.letsblog.api.cms.CmsAdapterFactory;
import com.letsblog.api.cms.CmsCredentials;
import com.letsblog.api.cms.CmsType;
import com.letsblog.api.domain.Post;
import com.letsblog.api.domain.Site;
import com.letsblog.api.repository.PostRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PostDeleteServiceTest {

    @Mock
    private SiteService siteService;
    @Mock
    private CmsAdapterFactory cmsAdapterFactory;
    @Mock
    private PostRepository postRepository;
    @Mock
    private CmsAdapter cmsAdapter;

    private PostDeleteService service;

    private final CmsCredentials.WordPressCredentials credentials =
            new CmsCredentials.WordPressCredentials("https://example.com", "admin", "SSH");

    @BeforeEach
    void setUp() {
        service = new PostDeleteService(siteService, cmsAdapterFactory, postRepository);

        Site site = new Site();
        site.setId(1L);
        site.setSiteKey("main");
        site.setCmsType(CmsType.WORDPRESS);

        lenient().when(siteService.getBySiteKey("main")).thenReturn(site);
        lenient().when(siteService.getCredentials("main")).thenReturn(credentials);
        lenient().when(cmsAdapterFactory.resolve(CmsType.WORDPRESS)).thenReturn(cmsAdapter);
    }

    @Test
    void delete_CmsAdapterで削除しstatusをtrashへ更新する() {
        Post post = new Post();
        post.setId(5L);
        post.setSiteId(1L);
        post.setWpPostId("99");
        post.setStatus("publish");
        when(postRepository.findBySiteIdAndWpPostId(1L, "99")).thenReturn(Optional.of(post));

        service.delete("main", "99");

        verify(cmsAdapter).deletePost(credentials, "99");
        ArgumentCaptor<Post> captor = ArgumentCaptor.forClass(Post.class);
        verify(postRepository).save(captor.capture());
        assertEquals("trash", captor.getValue().getStatus());
    }

    @Test
    void delete_ローカルにレコードがなくてもCmsAdapter側の削除は実行する() {
        when(postRepository.findBySiteIdAndWpPostId(1L, "99")).thenReturn(Optional.empty());

        Post result = service.delete("main", "99");

        verify(cmsAdapter).deletePost(credentials, "99");
        assertNull(result);
    }

    @Test
    void delete_CmsAdapterが例外を投げたらローカルレコードは更新されない() {
        org.mockito.Mockito.doThrow(new RuntimeException("failed"))
                .when(cmsAdapter).deletePost(any(), any());

        try {
            service.delete("main", "99");
        } catch (RuntimeException ignored) {
            // 例外自体はここでは検証対象外
        }

        verify(postRepository, org.mockito.Mockito.never()).save(any());
    }
}
