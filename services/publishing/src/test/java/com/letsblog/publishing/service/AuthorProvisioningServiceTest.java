package com.letsblog.publishing.service;

import com.letsblog.publishing.client.ProjectServiceClient;
import com.letsblog.publishing.cms.AuthorProvisioningRequest;
import com.letsblog.publishing.cms.CmsAdapter;
import com.letsblog.publishing.cms.CmsAdapterFactory;
import com.letsblog.publishing.cms.CmsApiException;
import com.letsblog.publishing.cms.CmsType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * AuthorProvisioningServiceの回帰テスト(issue #707、#575設計判断4の書き込み側)。
 */
@ExtendWith(MockitoExtension.class)
class AuthorProvisioningServiceTest {

    @Mock
    private ProjectServiceClient projectServiceClient;
    @Mock
    private CmsAdapterFactory cmsAdapterFactory;
    @Mock
    private CmsAdapter cmsAdapter;

    private AuthorProvisioningService service;

    @BeforeEach
    void setUp() {
        service = new AuthorProvisioningService(projectServiceClient, cmsAdapterFactory);
        ProjectServiceClient.SiteCredentialsBridge credentialsBridge = new ProjectServiceClient.SiteCredentialsBridge(
                1L, CmsType.WORDPRESS,
                Map.of("baseUrl", "https://example.com", "username", "admin", "transport", "SSH"));
        when(projectServiceClient.getCredentials("main")).thenReturn(credentialsBridge);
        when(cmsAdapterFactory.resolve(CmsType.WORDPRESS)).thenReturn(cmsAdapter);
    }

    @Test
    void provisionAuthor_権限があればCmsAdapterへ委譲する() {
        when(cmsAdapter.hasAuthorProvisioningCapability(any())).thenReturn(true);
        when(cmsAdapter.provisionAuthor(any(), any())).thenReturn("9");
        AuthorProvisioningRequest request = new AuthorProvisioningRequest(
                "member@example.com", "editor", null, null, null, null, null, null);

        String cmsAuthorId = service.provisionAuthor("main", request);

        assertEquals("9", cmsAuthorId);
    }

    @Test
    void provisionAuthor_権限がなければCmsApiExceptionを投げる() {
        when(cmsAdapter.hasAuthorProvisioningCapability(any())).thenReturn(false);
        AuthorProvisioningRequest request = new AuthorProvisioningRequest(
                "member@example.com", "editor", null, null, null, null, null, null);

        assertThrows(CmsApiException.class, () -> service.provisionAuthor("main", request));
    }
}
