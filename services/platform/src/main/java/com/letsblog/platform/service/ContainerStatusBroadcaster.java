package com.letsblog.platform.service;

import com.letsblog.platform.dto.ContainerStatusResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * legacy-apiから移設(issue #695、C10-3、元は issue #280)。ダッシュボードのコンテナ稼働状況を
 * Server-Sent Eventsでリアルタイム配信する。ConnectedServiceStatusBroadcasterと同じhubパターンを
 * 踏襲する。
 */
@Component
@Slf4j
public class ContainerStatusBroadcaster {

    private static final long BROADCAST_INTERVAL_MS = 15_000;

    private final ContainerStatusService containerStatusService;
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public ContainerStatusBroadcaster(ContainerStatusService containerStatusService) {
        this.containerStatusService = containerStatusService;
    }

    /** タイムアウトなし(0L)で登録する。接続断はonCompletion/onTimeout/onErrorで検知して除去する。 */
    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));

        sendTo(emitter, containerStatusService.listAll());
        return emitter;
    }

    @Scheduled(fixedRate = BROADCAST_INTERVAL_MS)
    public void broadcast() {
        if (emitters.isEmpty()) {
            return;
        }
        List<ContainerStatusResponse> statuses = containerStatusService.listAll();
        for (SseEmitter emitter : emitters) {
            sendTo(emitter, statuses);
        }
    }

    private void sendTo(SseEmitter emitter, List<ContainerStatusResponse> statuses) {
        try {
            emitter.send(SseEmitter.event().name("status").data(statuses));
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE配信に失敗したため接続を終了します: {}", e.getMessage());
            emitter.completeWithError(e);
            emitters.remove(emitter);
        }
    }
}
