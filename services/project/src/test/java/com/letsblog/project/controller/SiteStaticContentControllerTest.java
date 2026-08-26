package com.letsblog.project.controller;

import com.letsblog.project.domain.StaticContentType;
import com.letsblog.project.dto.GenerateStaticContentRequest;
import com.letsblog.project.dto.StaticContentResponse;
import com.letsblog.project.service.AdminAuthorizationService;
import com.letsblog.project.service.ForbiddenException;
import com.letsblog.project.service.StaticContentGenerationService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/** SiteStaticContentControllerの回帰テスト(issue #577 stage2、legacy-apiから移設)。 */
@ExtendWith(MockitoExtension.class)
class SiteStaticContentControllerTest {

    @Mock
    private StaticContentGenerationService staticContentGenerationService;
    @Mock
    private AdminAuthorizationService adminAuthorizationService;

    private SiteStaticContentController controller() {
        return new SiteStaticContentController(staticContentGenerationService, adminAuthorizationService);
    }

    @Test
    void list_一覧を返す() {
        StaticContentResponse response = new StaticContentResponse(
                1L, 1L, StaticContentType.PRIVACY_POLICY, "本文", LocalDateTime.now(), LocalDateTime.now());
        when(staticContentGenerationService.listBySite(1L)).thenReturn(List.of(response));

        List<StaticContentResponse> result = controller().list(1L);

        assertEquals(1, result.size());
    }

    @Test
    void generate_admin権限がなければForbidden() {
        doThrow(new ForbiddenException("admin権限が必要です")).when(adminAuthorizationService).requireAdmin();
        GenerateStaticContentRequest request = new GenerateStaticContentRequest(StaticContentType.PRIVACY_POLICY);

        assertThrows(ForbiddenException.class, () -> controller().generate(1L, request));
    }
}
