package com.letsblog.project.controller;

import com.letsblog.project.domain.StaticContentType;
import com.letsblog.project.dto.GenerateStaticContentRequest;
import com.letsblog.project.dto.StaticContentResponse;
import com.letsblog.project.service.AdminAuthorizationService;
import com.letsblog.project.service.ForbiddenException;
import com.letsblog.project.service.StaticContentGenerationService;
import com.letsblog.project.service.TextGenerationJobStarter;
import com.letsblog.project.dto.GenerationJobResponse;
import com.letsblog.project.dto.SaveStaticContentRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import java.time.Instant;
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
    @Mock
    private TextGenerationJobStarter textGenerationJobStarter;

    private SiteStaticContentController controller() {
        return new SiteStaticContentController(
                staticContentGenerationService, adminAuthorizationService, textGenerationJobStarter);
    }

    @Test
    void list_一覧を返す() {
        StaticContentResponse response = new StaticContentResponse(
                1L, 1L, StaticContentType.PRIVACY_POLICY, "本文", Instant.now(), Instant.now());
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

    // ---- issue #1409: 生成を非同期ジョブとして受理する / 「保存」で既存のstatic_contentへ書く ----

    @Test
    void generateJob_admin確認後にジョブとして受理し202を返す() {
        java.time.LocalDateTime created = java.time.LocalDateTime.of(2026, 10, 6, 1, 2, 3);
        when(textGenerationJobStarter.startStaticContent(1L, StaticContentType.PRIVACY_POLICY))
                .thenReturn(new com.letsblog.common.client.GenerationJobSummary(
                        9L, "static_content_generation", "running", created, created));

        ResponseEntity<GenerationJobResponse> response = controller().generateJob(
                1L, new GenerateStaticContentRequest(StaticContentType.PRIVACY_POLICY));

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals(9L, response.getBody().id());
        assertEquals("static_content_generation", response.getBody().type());
        verify(adminAuthorizationService).requireAdmin();
    }

    @Test
    void generateJob_admin権限がなければジョブを作らない() {
        doThrow(new ForbiddenException("admin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller().generateJob(
                1L, new GenerateStaticContentRequest(StaticContentType.PRIVACY_POLICY)));
        verifyNoInteractions(textGenerationJobStarter);
    }

    @Test
    void save_admin確認後に既存のstatic_contentへ保存する() {
        StaticContentResponse saved = new StaticContentResponse(
                1L, 1L, StaticContentType.OPERATOR_INFO, "保存する本文", Instant.now(), Instant.now());
        when(staticContentGenerationService.save(1L, StaticContentType.OPERATOR_INFO, "保存する本文")).thenReturn(saved);

        StaticContentResponse response = controller().save(
                1L, StaticContentType.OPERATOR_INFO, new SaveStaticContentRequest("保存する本文"));

        assertEquals(saved, response);
        verify(adminAuthorizationService).requireAdmin();
    }

    @Test
    void save_admin権限がなければ保存しない() {
        doThrow(new ForbiddenException("admin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller().save(
                1L, StaticContentType.OPERATOR_INFO, new SaveStaticContentRequest("本文")));
        verifyNoInteractions(staticContentGenerationService);
    }
}
