package com.letsblog.api.service;

import com.letsblog.api.dto.ConnectedServiceStatusResponse.Status;
import com.letsblog.api.dto.ContainerStatusResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ContainerStatusBroadcasterの回帰テスト(issue #280)。ConnectedServiceStatusBroadcasterTestと
 * 同じhubパターンを検証する。
 */
@ExtendWith(MockitoExtension.class)
class ContainerStatusBroadcasterTest {

    @Mock
    private ContainerStatusService containerStatusService;

    private final List<ContainerStatusResponse> sampleContainers =
            List.of(new ContainerStatusResponse("api", "api", Status.NORMAL, "running", "Up 2 hours"));

    @Test
    void subscribe_接続直後に現在の状態を送る() {
        when(containerStatusService.listAll()).thenReturn(sampleContainers);
        ContainerStatusBroadcaster broadcaster = new ContainerStatusBroadcaster(containerStatusService);

        SseEmitter emitter = broadcaster.subscribe();

        assertNotNull(emitter);
        verify(containerStatusService, times(1)).listAll();
    }

    @Test
    void broadcast_購読者がいなければチェックをスキップする() {
        ContainerStatusBroadcaster broadcaster = new ContainerStatusBroadcaster(containerStatusService);

        broadcaster.broadcast();

        verify(containerStatusService, times(0)).listAll();
    }

    @Test
    void broadcast_購読者がいれば全員分チェックして配信する() {
        when(containerStatusService.listAll()).thenReturn(sampleContainers);
        ContainerStatusBroadcaster broadcaster = new ContainerStatusBroadcaster(containerStatusService);
        broadcaster.subscribe();
        broadcaster.subscribe();

        broadcaster.broadcast();

        // subscribe()の初回送信2回 + broadcast()の1回分(全購読者へ1回のlistAll)で計3回
        verify(containerStatusService, times(3)).listAll();
    }
}
