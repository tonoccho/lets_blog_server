package com.letsblog.project.controller;

import com.letsblog.project.domain.EmbedTagType;
import com.letsblog.project.dto.GenerateTagDesignRequest;
import com.letsblog.project.dto.GenerateTagDesignResponse;
import com.letsblog.project.dto.SaveTagDesignSettingRequest;
import com.letsblog.project.dto.TagDesignSettingResponse;
import com.letsblog.project.dto.TagDesignSettingsOverviewResponse;
import com.letsblog.project.service.AdminAuthorizationService;
import com.letsblog.project.service.ForbiddenException;
import com.letsblog.project.service.TagDesignGenerationService;
import com.letsblog.project.service.TagDesignSettingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * issue #763: グローバル既定タグデザインAPIの認可とサービス委譲。
 *
 * <p>{@code AuthorizationMatrixIntegrationTest}が見るのは「JWTが無ければ401」までで、
 * 「有効だがadminでないJWTなら403」は見ていない。{@code requireAdmin()}の呼び出しが
 * 将来消えても既存テストでは検知できないため、{@link TagDesignSettingControllerTest}と
 * 同じ形でここに固定する。
 *
 * <p>プロジェクト単位が{@code requireProjectMemberOrAdmin}なのに対しこちらが
 * {@code requireAdmin}なのは、グローバル既定には判定に使えるプロジェクトメンバーシップが無く、
 * 影響範囲も未紐付けサイト全体に及ぶため。
 */
@ExtendWith(MockitoExtension.class)
class GlobalTagDesignSettingControllerTest {

    @Mock
    private TagDesignSettingService tagDesignSettingService;

    @Mock
    private TagDesignGenerationService tagDesignGenerationService;

    @Mock
    private AdminAuthorizationService adminAuthorizationService;

    private GlobalTagDesignSettingController controller() {
        return new GlobalTagDesignSettingController(
                tagDesignSettingService, tagDesignGenerationService, adminAuthorizationService);
    }

    @Test
    void getOverview_認可後にprojectId未指定でサービスへ委譲する() {
        TagDesignSettingsOverviewResponse expected =
                new TagDesignSettingsOverviewResponse(List.of(), List.of());
        when(tagDesignSettingService.getOverview(null)).thenReturn(expected);

        assertEquals(expected, controller().getOverview());

        verify(adminAuthorizationService).requireAdmin();
        verify(tagDesignSettingService).getOverview(isNull());
    }

    @Test
    void save_認可後にprojectId未指定でサービスへ委譲する() {
        SaveTagDesignSettingRequest request =
                new SaveTagDesignSettingRequest("light", "#111111", "#eeeeee", "#60a5fa", null, null);
        TagDesignSettingResponse expected = new TagDesignSettingResponse(
                EmbedTagType.TOC, "light", "#111111", "#eeeeee", "#60a5fa", null, null);
        when(tagDesignSettingService.save(isNull(), any(), any())).thenReturn(expected);

        assertEquals(expected, controller().save(EmbedTagType.TOC, request));

        verify(adminAuthorizationService).requireAdmin();
        verify(tagDesignSettingService).save(isNull(), any(), any());
    }

    @Test
    void generate_認可後にprojectId未指定でサービスへ委譲する() {
        GenerateTagDesignResponse expected = new GenerateTagDesignResponse("<div></div>", ".a{}");
        when(tagDesignGenerationService.generate(isNull(), any(), any(), any())).thenReturn(expected);

        assertEquals(expected, controller().generate(
                EmbedTagType.TOC, new GenerateTagDesignRequest("青くして")));

        verify(adminAuthorizationService).requireAdmin();
        verify(tagDesignSettingService).resolveHtmlTemplate(isNull(), any());
    }

    // ------------------------------------------------------------ 認可で弾かれる場合

    @Test
    void getOverview_非adminはForbiddenでサービスに到達しない() {
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です"))
                .when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller().getOverview());

        verify(tagDesignSettingService, org.mockito.Mockito.never()).getOverview(any());
    }

    @Test
    void save_非adminはForbiddenでサービスに到達しない() {
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です"))
                .when(adminAuthorizationService).requireAdmin();

        SaveTagDesignSettingRequest request =
                new SaveTagDesignSettingRequest("light", "#111111", "#eeeeee", "#60a5fa", null, null);

        assertThrows(ForbiddenException.class, () -> controller().save(EmbedTagType.TOC, request));

        verify(tagDesignSettingService, org.mockito.Mockito.never()).save(any(), any(), any());
    }

    @Test
    void generate_非adminはForbiddenでサービスに到達しない() {
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です"))
                .when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller().generate(
                EmbedTagType.TOC, new GenerateTagDesignRequest("青くして")));

        verify(tagDesignGenerationService, org.mockito.Mockito.never())
                .generate(any(), any(), any(), any());
    }
}
