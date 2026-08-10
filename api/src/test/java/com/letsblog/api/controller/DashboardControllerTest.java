package com.letsblog.api.controller;

import com.letsblog.api.dto.ConnectedServiceStatusDetailResponse;
import com.letsblog.api.dto.ConnectedServiceStatusResponse;
import com.letsblog.api.dto.ConnectedServiceStatusResponse.Status;
import com.letsblog.api.service.AdminAuthorizationService;
import com.letsblog.api.service.ConnectedServiceStatusBroadcaster;
import com.letsblog.api.service.ConnectedServiceStatusService;
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
 * DashboardControllerの回帰テスト(issue #199)。詳細診断エンドポイントがadmin限定であることを検証する。
 */
@ExtendWith(MockitoExtension.class)
class DashboardControllerTest {

    @Mock
    private ConnectedServiceStatusService connectedServiceStatusService;

    @Mock
    private ConnectedServiceStatusBroadcaster connectedServiceStatusBroadcaster;

    @Mock
    private AdminAuthorizationService adminAuthorizationService;

    private DashboardController controller() {
        return new DashboardController(
                connectedServiceStatusService, connectedServiceStatusBroadcaster, adminAuthorizationService);
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
}
