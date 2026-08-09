package com.letsblog.api.controller;

import com.letsblog.api.domain.EmbedTagType;
import com.letsblog.api.dto.SaveTagDesignSettingRequest;
import com.letsblog.api.dto.TagDesignSettingResponse;
import com.letsblog.api.dto.TagDesignSettingsOverviewResponse;
import com.letsblog.api.service.AdminAuthorizationService;
import com.letsblog.api.service.ForbiddenException;
import com.letsblog.api.service.TagDesignSettingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TagDesignSettingControllerTest {

    @Mock
    private TagDesignSettingService tagDesignSettingService;

    @Mock
    private AdminAuthorizationService adminAuthorizationService;

    private TagDesignSettingController controller() {
        return new TagDesignSettingController(tagDesignSettingService, adminAuthorizationService);
    }

    @Test
    void getOverview_認可後にサービスへ委譲する() {
        TagDesignSettingController controller = controller();
        TagDesignSettingsOverviewResponse overview = new TagDesignSettingsOverviewResponse(List.of(), List.of());
        when(tagDesignSettingService.getOverview(1L)).thenReturn(overview);

        TagDesignSettingsOverviewResponse response = controller.getOverview(1L);

        assertEquals(overview, response);
        verify(adminAuthorizationService).requireProjectMemberOrAdmin(1L);
    }

    @Test
    void getOverview_認可拒否ならForbidden() {
        TagDesignSettingController controller = controller();
        doThrow(new ForbiddenException("拒否")).when(adminAuthorizationService).requireProjectMemberOrAdmin(1L);

        assertThrows(ForbiddenException.class, () -> controller.getOverview(1L));
    }

    @Test
    void save_認可後にサービスへ委譲する() {
        TagDesignSettingController controller = controller();
        SaveTagDesignSettingRequest request = new SaveTagDesignSettingRequest("dark", "#111111", "#eeeeee", "#60a5fa");
        TagDesignSettingResponse expected =
                new TagDesignSettingResponse(EmbedTagType.TOC, "dark", "#111111", "#eeeeee", "#60a5fa");
        when(tagDesignSettingService.save(1L, EmbedTagType.TOC, request)).thenReturn(expected);

        TagDesignSettingResponse response = controller.save(1L, EmbedTagType.TOC, request);

        assertEquals(expected, response);
        verify(adminAuthorizationService).requireProjectMemberOrAdmin(1L);
    }

    @Test
    void save_認可拒否ならForbidden() {
        TagDesignSettingController controller = controller();
        doThrow(new ForbiddenException("拒否")).when(adminAuthorizationService).requireProjectMemberOrAdmin(1L);
        SaveTagDesignSettingRequest request = new SaveTagDesignSettingRequest("dark", "#111111", "#eeeeee", "#60a5fa");

        assertThrows(ForbiddenException.class, () -> controller.save(1L, EmbedTagType.TOC, request));
        verify(tagDesignSettingService, org.mockito.Mockito.never()).save(any(), any(), any());
    }
}
