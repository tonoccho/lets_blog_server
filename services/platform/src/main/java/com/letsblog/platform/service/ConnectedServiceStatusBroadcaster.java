package com.letsblog.platform.service;

import com.letsblog.platform.dto.ConnectedServiceStatusResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * legacy-apiから移設(issue #695、C10-3、元は issue #198)。ダッシュボードの接続サービス稼働状況を
 * Server-Sent Eventsで配信する。WebSocketではなくSSEを選んだのは、移設元issue本文で両方式が
 * 許容されており、既存インフラ(nginxのAPIリバースプロキシ、gatewayのストリーミング転送)を
 * 変更せずに実現できるため。接続中のクライアントを保持し、定期チェックの結果を全員へ
 * ブロードキャストする単純なhub。
 */
@Component
@Slf4j
public class ConnectedServiceStatusBroadcaster {

    private static final long BROADCAST_INTERVAL_MS = 15_000;

    private final ConnectedServiceStatusService connectedServiceStatusService;
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public ConnectedServiceStatusBroadcaster(ConnectedServiceStatusService connectedServiceStatusService) {
        this.connectedServiceStatusService = connectedServiceStatusService;
    }

    /** タイムアウトなし(0L)で登録する。接続断はonCompletion/onTimeout/onErrorで検知して除去する。 */
    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));

        sendTo(emitter, connectedServiceStatusService.checkAll());
        return emitter;
    }

    @Scheduled(fixedRate = BROADCAST_INTERVAL_MS)
    public void broadcast() {
        if (emitters.isEmpty()) {
            return;
        }
        List<ConnectedServiceStatusResponse> statuses = connectedServiceStatusService.checkAll();
        for (SseEmitter emitter : emitters) {
            sendTo(emitter, statuses);
        }
    }

    private void sendTo(SseEmitter emitter, List<ConnectedServiceStatusResponse> statuses) {
        try {
            emitter.send(SseEmitter.event().name("status").data(statuses));
        } catch (IOException | IllegalStateException e) {
            // クライアントが既に切断済みの場合に発生しうる。ログにはdebugレベルで残し、
            // 対象emitterを配信対象から除去する(onErrorコールバックでも除去されるが、
            // completeWithErrorを呼ぶことで確実にクリーンアップする)。
            log.debug("SSE配信に失敗したため接続を終了します: {}", e.getMessage());
            emitter.completeWithError(e);
            emitters.remove(emitter);
        }
    }
}
