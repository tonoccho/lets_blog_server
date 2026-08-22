package com.letsblog.api.controller;

import com.letsblog.api.dto.ConnectedServiceStatusDetailResponse;
import com.letsblog.api.dto.ConnectedServiceStatusResponse;
import com.letsblog.api.dto.ConnectedServiceStatusResponse.Status;
import com.letsblog.api.dto.ContainerStatusResponse;
import com.letsblog.api.service.AdminAuthorizationService;
import com.letsblog.api.service.ConnectedServiceStatusBroadcaster;
import com.letsblog.api.service.ConnectedServiceStatusService;
import com.letsblog.api.service.ContainerStatusBroadcaster;
import com.letsblog.api.service.ContainerStatusService;
import com.letsblog.api.service.ForbiddenException;
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
 * DashboardControllerの回帰テスト(issue #199, #280)。詳細診断エンドポイントがadmin限定であることと、
 * コンテナ稼働状況エンドポイントがContainerStatusServiceへ委譲することを検証する。
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
