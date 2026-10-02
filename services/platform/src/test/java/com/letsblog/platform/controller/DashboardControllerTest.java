package com.letsblog.platform.controller;

import com.letsblog.platform.dto.ConnectedServiceStatusDetailResponse;
import com.letsblog.platform.dto.ConnectedServiceStatusResponse;
import com.letsblog.platform.dto.ConnectedServiceStatusResponse.Status;
import com.letsblog.platform.dto.ContainerStatusResponse;
import com.letsblog.platform.service.AdminAuthorizationService;
import com.letsblog.platform.service.ConnectedServiceStatusBroadcaster;
import com.letsblog.platform.service.ConnectedServiceStatusService;
import com.letsblog.platform.service.ContainerStatusBroadcaster;
import com.letsblog.platform.service.ContainerStatusService;
import com.letsblog.platform.service.ForbiddenException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * legacy-apiから移設(issue #695、C10-3)。DashboardControllerの回帰テスト(元は issue #199, #280)。
 * 詳細診断エンドポイントがadmin限定であることと、コンテナ稼働状況エンドポイントが
 * ContainerStatusServiceへ委譲することを検証する。
 */
@ExtendWith(MockitoExtension.class)
class DashboardControllerTest {

    @Mock
    private ConnectedServiceStatusService connectedServiceStatusService;

    @Mock
    private ConnectedServiceStatusBroadcaster connectedServiceStatusBroadcaster;

    @Mock
    private ContainerStatusService containerStatusService;

    @Mock
    private ContainerStatusBroadcaster containerStatusBroadcaster;

    @Mock
    private AdminAuthorizationService adminAuthorizationService;

    private DashboardController controller() {
        return new DashboardController(
                connectedServiceStatusService, connectedServiceStatusBroadcaster,
                containerStatusService, containerStatusBroadcaster, adminAuthorizationService);
    }

    @Test
    void getServiceStatus_稼働状況の3値のみを返す() {
        List<ConnectedServiceStatusResponse> statuses =
                List.of(new ConnectedServiceStatusResponse("database", "データベース", Status.NORMAL));
        when(connectedServiceStatusService.checkAll()).thenReturn(statuses);

        List<ConnectedServiceStatusResponse> result = controller().getServiceStatus();

        assertEquals(statuses, result);
    }

    @Test
    void getServiceStatusDetail_admin権限があれば詳細診断情報を返す() {
        List<ConnectedServiceStatusDetailResponse> details = List.of(
                new ConnectedServiceStatusDetailResponse(
                        "database", "データベース", Status.NORMAL, 5L, null, null, null, Instant.now(), null, null));
        when(connectedServiceStatusService.checkAllDetailed()).thenReturn(details);

        List<ConnectedServiceStatusDetailResponse> result = controller().getServiceStatusDetail();

        assertEquals(details, result);
        verify(adminAuthorizationService).requireAdmin();
    }

    @Test
    void getServiceStatusDetail_admin権限がなければForbiddenExceptionを投げる() {
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です"))
                .when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller().getServiceStatusDetail());
    }

    @Test
    void getContainerStatus_admin権限があればContainerStatusServiceの結果をそのまま返す() {
        List<ContainerStatusResponse> containers =
                List.of(new ContainerStatusResponse("api", "api", Status.NORMAL, "running", "Up 2 hours"));
        when(containerStatusService.listAll()).thenReturn(containers);

        List<ContainerStatusResponse> result = controller().getContainerStatus();

        assertEquals(containers, result);
        verify(adminAuthorizationService).requireAdmin();
    }

    // ---- issue #830: コンテナ名と稼働状況はインフラの構成情報なのでadmin限定にした ----

    @Test
    void getContainerStatus_admin権限がなければコンテナ一覧を読まずに拒否する() {
        // #816 のQAで、無効化された利用者に全コンテナ名と稼働状況が見え続けることが確認されている。
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です"))
                .when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller().getContainerStatus());

        verifyNoInteractions(containerStatusService);
    }

    @Test
    void streamContainerStatus_admin権限がなければ購読させない() {
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です"))
                .when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller().streamContainerStatus());

        verifyNoInteractions(containerStatusBroadcaster);
    }

    @Test
    void getServiceStatus_admin以外でも要約は返す() {
        // 詳細(getServiceStatusDetail)はadmin限定だが、要約は共通ダッシュボード向けなので開けておく。
        List<ConnectedServiceStatusResponse> statuses =
                List.of(new ConnectedServiceStatusResponse("database", "データベース", Status.NORMAL));
        when(connectedServiceStatusService.checkAll()).thenReturn(statuses);

        assertEquals(statuses, controller().getServiceStatus());
        verifyNoInteractions(adminAuthorizationService);
    }
}
