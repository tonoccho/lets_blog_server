package com.letsblog.api.service;

import com.letsblog.api.dto.ConnectedServiceStatusResponse;
import com.letsblog.api.dto.ConnectedServiceStatusResponse.Status;
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
 * ConnectedServiceStatusBroadcasterの回帰テスト(issue #198)。
 * subscribe時に即座に現在の状態を送ること、broadcast時に購読中の全員へ配信すること、
 * 購読者がいない場合はチェック自体をスキップすることを検証する。
 */
@ExtendWith(MockitoExtension.class)
class ConnectedServiceStatusBroadcasterTest {

    @Mock
    private ConnectedServiceStatusService connectedServiceStatusService;

    private final List<ConnectedServiceStatusResponse> sampleStatuses =
            List.of(new ConnectedServiceStatusResponse("database", "データベース", Status.NORMAL));

    @Test
    void subscribe_接続直後に現在の状態を送る() {
        when(connectedServiceStatusService.checkAll()).thenReturn(sampleStatuses);
        ConnectedServiceStatusBroadcaster broadcaster =
                new ConnectedServiceStatusBroadcaster(connectedServiceStatusService);

        SseEmitter emitter = broadcaster.subscribe();

        assertNotNull(emitter);
        verify(connectedServiceStatusService, times(1)).checkAll();
    }

    @Test
    void broadcast_購読者がいなければチェックをスキップする() {
        ConnectedServiceStatusBroadcaster broadcaster =
                new ConnectedServiceStatusBroadcaster(connectedServiceStatusService);

        broadcaster.broadcast();

        verify(connectedServiceStatusService, times(0)).checkAll();
    }

    @Test
    void broadcast_購読者がいれば全員分チェックして配信する() {
        when(connectedServiceStatusService.checkAll()).thenReturn(sampleStatuses);
        ConnectedServiceStatusBroadcaster broadcaster =
                new ConnectedServiceStatusBroadcaster(connectedServiceStatusService);
        broadcaster.subscribe();
        broadcaster.subscribe();

        broadcaster.broadcast();

        // subscribe()の初回送信2回 + broadcast()の1回分(全購読者へ1回のcheckAll)で計3回
        verify(connectedServiceStatusService, times(3)).checkAll();
    }
}
