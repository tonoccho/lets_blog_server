package com.letsblog.platform.controller;

import com.letsblog.platform.service.ProjectPluginRegistrationService;
import com.letsblog.platform.service.ProjectPluginRegistrationService.ProjectRegistrationView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** ProjectPluginRegistrationController(issue #1808)。認可はサービス層(requireProjectMemberOrAdmin)が担い、ここは委譲だけを持つ。 */
@ExtendWith(MockitoExtension.class)
class ProjectPluginRegistrationControllerTest {

    @Mock
    private ProjectPluginRegistrationService service;

    private static final ProjectRegistrationView VIEW =
            new ProjectRegistrationView(1L, "LLM", "社内", "http://gpu-pc:11434/v1", false, null, true);

    @Test
    void 一覧_追加_編集_削除_使用中の切り替え_認証情報の消去をサービスへ委譲する() {
        ProjectPluginRegistrationController controller = new ProjectPluginRegistrationController(service);
        when(service.list(10L)).thenReturn(List.of(VIEW));
        when(service.create(10L, "LLM", "社内", "http://gpu-pc:11434/v1", null, "sk")).thenReturn(VIEW);
        when(service.update(10L, 1L, "社内", "http://gpu-pc:11434/v1", null, null)).thenReturn(VIEW);
        when(service.activate(10L, 1L)).thenReturn(VIEW);
        when(service.clearCredential(10L, 1L)).thenReturn(VIEW);

        assertEquals(List.of(VIEW), controller.list(10L));
        assertSame(VIEW, controller.create(10L, new ProjectPluginRegistrationController.CreateRequest(
                "LLM", "社内", "http://gpu-pc:11434/v1", null, "sk")));
        assertSame(VIEW, controller.update(10L, 1L, new ProjectPluginRegistrationController.UpdateRequest(
                "社内", "http://gpu-pc:11434/v1", null, null)));
        assertSame(VIEW, controller.activate(10L, 1L));
        assertSame(VIEW, controller.clearCredential(10L, 1L));
        assertEquals(HttpStatus.NO_CONTENT, controller.delete(10L, 1L).getStatusCode());
        verify(service).delete(10L, 1L);
    }
}
