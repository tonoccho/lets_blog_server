package com.letsblog.api.service;

import com.letsblog.api.dto.ConnectedServiceStatusResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * ダッシュボードの接続サービス稼働状況をServer-Sent Eventsで配信する(issue #198)。
 * WebSocketではなくSSEを選んだのは、issue本文で両方式が許容されており、既存インフラ
 * (nginxのAPIリバースプロキシ、Route Handler経由の認証中継)を変更せずに実現できるため。
 * 接続中のクライアントを保持し、定期チェックの結果を全員へブロードキャストする単純なhub。
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
