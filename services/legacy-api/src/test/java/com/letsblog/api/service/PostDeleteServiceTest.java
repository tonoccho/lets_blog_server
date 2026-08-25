package com.letsblog.api.service;

import com.letsblog.api.client.ContentServiceClient;
import com.letsblog.api.cms.CmsAdapter;
import com.letsblog.api.cms.CmsAdapterFactory;
import com.letsblog.api.cms.CmsCredentials;
import com.letsblog.api.cms.CmsType;
import com.letsblog.api.domain.Site;
import com.letsblog.api.messaging.DomainEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PostDeleteServiceTest {

    @Mock
    private SiteService siteService;
    @Mock
    private CmsAdapterFactory cmsAdapterFactory;
    @Mock
    private ContentServiceClient contentServiceClient;
    @Mock
    private CmsAdapter cmsAdapter;
    @Mock
    private DomainEventPublisher domainEventPublisher;

    private PostDeleteService service;

    private final CmsCredentials.WordPressCredentials credentials =
            new CmsCredentials.WordPressCredentials("https://example.com", "admin", "SSH");

    @BeforeEach
    void setUp() {
        service = new PostDeleteService(siteService, cmsAdapterFactory, contentServiceClient, domainEventPublisher);

        Site site = new Site();
        site.setId(1L);
        site.setSiteKey("main");
        site.setCmsType(CmsType.WORDPRESS);

        lenient().when(siteService.getBySiteKey("main")).thenReturn(site);
        lenient().when(siteService.getCredentials("main")).thenReturn(credentials);
        lenient().when(cmsAdapterFactory.resolve(CmsType.WORDPRESS)).thenReturn(cmsAdapter);
    }

    @Test
    void delete_CmsAdapterで削除しcontent_serviceへtrash反映を依頼する() {
        service.delete("main", "99");

        verify(cmsAdapter).deletePost(credentials, "99");
        verify(contentServiceClient).markTrashed(1L, "99");
        verify(domainEventPublisher).publishPostDeleted(1L, "99");
    }

    @Test
    void delete_CmsAdapterが例外を投げたらcontent_serviceへの反映もイベント発行も行われない() {
        Mockito.doThrow(new RuntimeException("failed"))
                .when(cmsAdapter).deletePost(any(), any());

        try {
            service.delete("main", "99");
        } catch (RuntimeException ignored) {
            // 例外自体はここでは検証対象外
        }

        verify(contentServiceClient, never()).markTrashed(ArgumentMatchers.anyLong(), ArgumentMatchers.anyString());
        verify(domainEventPublisher, never()).publishPostDeleted(ArgumentMatchers.anyLong(), ArgumentMatchers.anyString());
    }
}
