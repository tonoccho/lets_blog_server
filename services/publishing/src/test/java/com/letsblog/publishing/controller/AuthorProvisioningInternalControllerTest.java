package com.letsblog.publishing.controller;

import com.letsblog.publishing.dto.AuthorProvisioningBridgeRequest;
import com.letsblog.publishing.dto.AuthorProvisioningBridgeResponse;
import com.letsblog.publishing.service.AuthorProvisioningService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthorProvisioningInternalControllerTest {

    @Mock
    private AuthorProvisioningService authorProvisioningService;

    private AuthorProvisioningInternalController controller() {
        return new AuthorProvisioningInternalController(authorProvisioningService);
    }

    @Test
    void provisionAuthor_サービスへ委譲し結果を返す() {
        when(authorProvisioningService.provisionAuthor(eq("main"), any())).thenReturn("9");
        AuthorProvisioningBridgeRequest request = new AuthorProvisioningBridgeRequest(
                "member@example.com", "editor", "太郎", "山田", "山田太郎", null, null, null);

        AuthorProvisioningBridgeResponse response = controller().provisionAuthor("main", request);

        assertEquals("9", response.cmsAuthorId());
        ArgumentCaptor<com.letsblog.publishing.cms.AuthorProvisioningRequest> captor =
                ArgumentCaptor.forClass(com.letsblog.publishing.cms.AuthorProvisioningRequest.class);
        verify(authorProvisioningService).provisionAuthor(eq("main"), captor.capture());
        assertEquals("member@example.com", captor.getValue().email());
        assertEquals("editor", captor.getValue().wpRole());
    }
}
