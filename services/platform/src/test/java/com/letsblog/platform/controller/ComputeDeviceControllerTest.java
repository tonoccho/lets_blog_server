package com.letsblog.platform.controller;

import com.letsblog.platform.dto.ApplyComputeDeviceRequest;
import com.letsblog.platform.dto.ComputeDevice;
import com.letsblog.platform.dto.ComputeDeviceStatusResponse;
import com.letsblog.platform.dto.ComputeDeviceStatusResponse.ApplyState;
import com.letsblog.platform.dto.ComputeDeviceStatusResponse.ApplyStatus;
import com.letsblog.platform.dto.ComputeDeviceStatusResponse.CurrentDevice;
import com.letsblog.platform.service.AdminAuthorizationService;
import com.letsblog.platform.service.ComputeDeviceService;
import com.letsblog.platform.service.ForbiddenException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 演算デバイス切り替えAPIは管理者だけが参照・適用できる(issue #1399 要件7)。 */
@ExtendWith(MockitoExtension.class)
class ComputeDeviceControllerTest {

    @Mock
    private ComputeDeviceService service;

    @Mock
    private AdminAuthorizationService adminAuthorizationService;

    private static ComputeDeviceStatusResponse status(ApplyState state) {
        return new ComputeDeviceStatusResponse("comfyui", CurrentDevice.GPU, false, true, true, null, null,
                new ApplyStatus(state, null, null, null, null));
    }

    private ComputeDeviceController controller() {
        return new ComputeDeviceController(service, adminAuthorizationService);
    }

    @Test
    void 参照は管理者確認のあとサービスへ委譲する() {
        when(service.getStatus("comfyui")).thenReturn(status(ApplyState.IDLE));

        ComputeDeviceStatusResponse result = controller().getStatus("comfyui");

        assertEquals(CurrentDevice.GPU, result.currentDevice());
        InOrder order = inOrder(adminAuthorizationService, service);
        order.verify(adminAuthorizationService).requireAdmin();
        order.verify(service).getStatus("comfyui");
    }

    @Test
    void 適用は管理者確認のあと受け付けて202を返す() {
        when(service.apply("comfyui", ComputeDevice.CPU)).thenReturn(status(ApplyState.APPLYING));

        ResponseEntity<ComputeDeviceStatusResponse> response =
                controller().apply("comfyui", new ApplyComputeDeviceRequest(ComputeDevice.CPU));

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals(ApplyState.APPLYING, response.getBody().apply().state());
        InOrder order = inOrder(adminAuthorizationService, service);
        order.verify(adminAuthorizationService).requireAdmin();
        order.verify(service).apply("comfyui", ComputeDevice.CPU);
    }

    @Test
    void 管理者でなければ参照も適用も403でサービスに触れない() {
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です"))
                .when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller().getStatus("comfyui"));
        assertThrows(ForbiddenException.class,
                () -> controller().apply("comfyui", new ApplyComputeDeviceRequest(ComputeDevice.GPU)));

        verifyNoInteractions(service);
    }
}
