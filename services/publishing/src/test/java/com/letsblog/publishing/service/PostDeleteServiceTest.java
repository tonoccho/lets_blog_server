package com.letsblog.publishing.service;

import com.letsblog.publishing.client.ContentServiceClient;
import com.letsblog.publishing.client.ProjectServiceClient;
import com.letsblog.publishing.cms.CmsAdapter;
import com.letsblog.publishing.cms.CmsAdapterFactory;
import com.letsblog.publishing.cms.CmsCredentials;
import com.letsblog.publishing.cms.CmsType;
import com.letsblog.publishing.messaging.DomainEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(MockitoExtension.class)
class PostDeleteServiceTest {

    @Mock
    private ProjectServiceClient projectServiceClient;
    @Mock
    private CmsAdapterFactory cmsAdapterFactory;
    @Mock
    private ContentServiceClient contentServiceClient;
    @Mock
    private CmsAdapter cmsAdapter;
    @Mock
    private DomainEventPublisher domainEventPublisher;
    @Mock
    private AdminAuthorizationService adminAuthorizationService;

    private PostDeleteService service;

    private final CmsCredentials.WordPressCredentials credentials =
            new CmsCredentials.WordPressCredentials("https://example.com", "admin", "SSH");

    @BeforeEach
    void setUp() {
        service = new PostDeleteService(projectServiceClient, cmsAdapterFactory, contentServiceClient, domainEventPublisher,
                adminAuthorizationService);

        ProjectServiceClient.SiteBridge site =
                new ProjectServiceClient.SiteBridge(1L, "main", "Main", "https://example.com", CmsType.WORDPRESS, false, null);
        ProjectServiceClient.SiteCredentialsBridge credentialsBridge = new ProjectServiceClient.SiteCredentialsBridge(
                1L, CmsType.WORDPRESS,
                Map.of("baseUrl", "https://example.com", "username", "admin", "transport", "SSH"));

        lenient().when(projectServiceClient.getSiteByKey("main")).thenReturn(site);
        lenient().when(projectServiceClient.getCredentials("main")).thenReturn(credentialsBridge);
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

    @Test
    void delete_プロジェクトメンバーでもadminでもなければCMSへ触れずに拒否する() {
        // issue #830: 投稿削除は「認証済みなら誰でも」ではなく、サイトが属するプロジェクトの
        // メンバー(またはadmin)に限定する。CMSへの副作用が始まる前に弾まれることを確かめる。
        Mockito.when(projectServiceClient.findProjectIdBySiteId(1L)).thenReturn(7L);
        Mockito.doThrow(new ForbiddenException("この操作にはプロジェクトメンバーまたはadmin権限が必要です"))
                .when(adminAuthorizationService).requireProjectMemberOrAdminForSite(7L);

        assertThrows(ForbiddenException.class, () -> service.delete("main", "99"));

        verify(cmsAdapter, never()).deletePost(any(), any());
        verify(contentServiceClient, never()).markTrashed(ArgumentMatchers.anyLong(), ArgumentMatchers.anyString());
        verify(domainEventPublisher, never()).publishPostDeleted(ArgumentMatchers.anyLong(), ArgumentMatchers.anyString());
    }

    @Test
    void delete_サイトが属するプロジェクトのIDで認可判定を行う() {
        Mockito.when(projectServiceClient.findProjectIdBySiteId(1L)).thenReturn(7L);

        service.delete("main", "99");

        verify(adminAuthorizationService).requireProjectMemberOrAdminForSite(7L);
    }
}
