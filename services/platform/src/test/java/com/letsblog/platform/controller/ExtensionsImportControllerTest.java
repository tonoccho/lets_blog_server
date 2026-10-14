package com.letsblog.platform.controller;

import com.letsblog.platform.service.ExtensionsConnectionImportService;
import com.letsblog.platform.service.ExtensionsConnectionImportService.ApplyResult;
import com.letsblog.platform.service.ExtensionsConnectionImportService.FetchResult;
import com.letsblog.platform.service.ExtensionsConnectionImportService.Route;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** ExtensionsImportController(issue #1783)。認可はサービス層(requireAdmin)が担い、ここは委譲と入力の解釈だけを持つ。 */
@ExtendWith(MockitoExtension.class)
class ExtensionsImportControllerTest {

    @Mock
    private ExtensionsConnectionImportService importService;

    @Test
    void fetchは経路を解釈してサービスへ委譲する() {
        FetchResult expected = new FetchResult(List.of(), List.of());
        when(importService.fetch("http://lbx-portal:3100", Route.SAME_PC)).thenReturn(expected);
        when(importService.fetch("http://lbx-portal:3100", Route.OTHER_HOST)).thenReturn(expected);
        ExtensionsImportController controller = new ExtensionsImportController(importService);

        assertSame(expected, controller.fetch(new ExtensionsImportController.FetchRequest("http://lbx-portal:3100", "SAME_PC")));
        assertSame(expected, controller.fetch(new ExtensionsImportController.FetchRequest("http://lbx-portal:3100", "OTHER_HOST")));
    }

    @Test
    void fetchの経路が不明なら例外でサービスを呼ばない() {
        ExtensionsImportController controller = new ExtensionsImportController(importService);

        for (String route : new String[] {null, "", "ELSEWHERE"}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> controller.fetch(new ExtensionsImportController.FetchRequest("http://lbx-portal:3100", route)));
            assertTrue(e.getMessage().contains("経路"), e.getMessage());
        }
        verifyNoInteractions(importService);
    }

    @Test
    void applyはサービスへ委譲する() {
        ApplyResult expected = new ApplyResult(List.of("comfyui_base_url"), List.of());
        Map<String, String> values = Map.of("comfyui_base_url", "http://lbx-comfyui:8188");
        when(importService.apply("http://lbx-portal:3100", values)).thenReturn(expected);
        ExtensionsImportController controller = new ExtensionsImportController(importService);

        assertSame(expected, controller.apply(new ExtensionsImportController.ApplyRequest("http://lbx-portal:3100", values)));
        verify(importService).apply("http://lbx-portal:3100", values);
    }
}
