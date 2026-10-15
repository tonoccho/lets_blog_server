package com.letsblog.platform.controller;

import com.letsblog.platform.service.PluginRegistrationService;
import com.letsblog.platform.service.PluginRegistrationService.RegistrationView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** PluginRegistrationController(issue #1807)。認可はサービス層(requireAdmin)が担い、ここは委譲だけを持つ。 */
@ExtendWith(MockitoExtension.class)
class PluginRegistrationControllerTest {

    @Mock
    private PluginRegistrationService service;

    private static final RegistrationView VIEW =
            new RegistrationView(1L, "LLM", "社内", "http://lbx-ollama:11434/v1", false, null);

    @Test
    void 一覧_追加_編集_削除_使用中の切り替えをサービスへ委譲する() {
        PluginRegistrationController controller = new PluginRegistrationController(service);
        when(service.list()).thenReturn(List.of(VIEW));
        when(service.create("LLM", "社内", "http://lbx-ollama:11434/v1", null)).thenReturn(VIEW);
        when(service.update(1L, "社内", "http://lbx-ollama:11434/v1", null)).thenReturn(VIEW);
        when(service.activate(1L)).thenReturn(VIEW);

        assertEquals(List.of(VIEW), controller.list());
        assertSame(VIEW, controller.create(new PluginRegistrationController.CreateRequest(
                "LLM", "社内", "http://lbx-ollama:11434/v1", null)));
        assertSame(VIEW, controller.update(1L, new PluginRegistrationController.UpdateRequest(
                "社内", "http://lbx-ollama:11434/v1", null)));
        assertSame(VIEW, controller.activate(1L));
        ResponseEntity<Void> deleted = controller.delete(1L);

        assertEquals(HttpStatus.NO_CONTENT, deleted.getStatusCode());
        verify(service).delete(1L);
    }
}
