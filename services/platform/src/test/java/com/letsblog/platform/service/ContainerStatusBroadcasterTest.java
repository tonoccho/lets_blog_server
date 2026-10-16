package com.letsblog.platform.service;

import com.letsblog.platform.testsupport.FakeHttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * legacy-apiから移設(issue #695、C10-3)。ContainerStatusBroadcasterの回帰テスト(元は issue #280)。
 * ConnectedServiceStatusBroadcasterTestと同じhubパターンを検証する。
 *
 * <p>issue #1825: ContainerStatusServiceは実物で、docker-socket-proxy(HTTP)だけをサーバー側のフェイクで模す。
 * 「何回チェックしたか」は、フェイクが受けた{@code /containers/json}の要求数で数える。
 */
class ContainerStatusBroadcasterTest {

    private FakeHttpServer docker;
    private ContainerStatusBroadcaster broadcaster;

    @BeforeEach
    void setUp() {
        docker = new FakeHttpServer();
        docker.json("GET", "/containers/json",
                "[{\"Names\":[\"/lbs-api\"],\"State\":\"running\",\"Status\":\"Up 2 hours\"}]");
        broadcaster = new ContainerStatusBroadcaster(
                new ContainerStatusService(RestClient.builder().baseUrl(docker.baseUrl()), ""));
    }

    @AfterEach
    void tearDown() {
        docker.close();
    }

    private int checks() {
        return docker.requests("GET", "/containers/json").size();
    }

    @Test
    void subscribe_接続直後に現在の状態を送る() {
        SseEmitter emitter = broadcaster.subscribe();

        assertNotNull(emitter);
        assertEquals(1, checks());
    }

    @Test
    void broadcast_購読者がいなければチェックをスキップする() {
        broadcaster.broadcast();

        assertEquals(0, checks());
    }

    @Test
    void broadcast_購読者がいれば全員分チェックして配信する() {
        broadcaster.subscribe();
        broadcaster.subscribe();

        broadcaster.broadcast();

        // subscribe()の初回送信2回 + broadcast()の1回分(全購読者へ1回のチェック)で計3回
        assertEquals(3, checks());
    }

    @Test
    void 配信できなくなった購読者は除去され_次からはチェックをスキップする() {
        SseEmitter emitter = broadcaster.subscribe();
        emitter.complete();

        // 完了済みのemitterへの送信はIllegalStateExceptionになり、購読者から外される。
        broadcaster.broadcast();
        assertEquals(2, checks());

        broadcaster.broadcast();
        assertEquals(2, checks());
    }
}
