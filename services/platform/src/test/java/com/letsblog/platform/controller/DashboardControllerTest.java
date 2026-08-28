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
                        "database", "データベース", Status.NORMAL, 5L, null, null, null, Instant.now()));
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
    void getContainerStatus_ContainerStatusServiceの結果をそのまま返す() {
        List<ContainerStatusResponse> containers =
                List.of(new ContainerStatusResponse("api", "api", Status.NORMAL, "running", "Up 2 hours"));
        when(containerStatusService.listAll()).thenReturn(containers);

        List<ContainerStatusResponse> result = controller().getContainerStatus();

        assertEquals(containers, result);
    }
}
