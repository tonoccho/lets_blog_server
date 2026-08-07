package com.letsblog.api.controller;

import com.letsblog.api.dto.CustomTagResponse;
import com.letsblog.api.service.CustomTagService;
import com.letsblog.api.service.CustomTagGenerationService;
import com.letsblog.api.service.CustomTagValidationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomTagControllerTest {

    @Mock
    private CustomTagService customTagService;

    @Mock
    private CustomTagGenerationService customTagGenerationService;

    @Mock
    private CustomTagValidationService customTagValidationService;

    private CustomTagController controller() {
        return new CustomTagController(customTagService, customTagGenerationService, customTagValidationService);
    }

    @Test
    void list_projectId未指定でも呼び出せる() {
        CustomTagController controller = controller();
        when(customTagService.list(null)).thenReturn(List.of());

        List<CustomTagResponse> response = controller.list(null);

        assertEquals(0, response.size());
        verify(customTagService).list(null);
    }

    @Test
    void list_projectId指定時はそのまま渡される() {
        CustomTagController controller = controller();
        when(customTagService.list(5L)).thenReturn(List.of());

        controller.list(5L);

        verify(customTagService).list(5L);
    }
}
