package com.letsblog.platform.service;

import com.letsblog.platform.testsupport.StatusFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.sql.DataSource;
import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * legacy-apiから移設(issue #695、C10-3)。ConnectedServiceStatusBroadcasterの回帰テスト
 * (元は issue #198)。subscribe時に即座に現在の状態を送ること、broadcast時に購読中の全員へ
 * 配信すること、購読者がいない場合はチェック自体をスキップすることを検証する。
 *
 * <p>issue #1825: ConnectedServiceStatusServiceは実物で、HTTP(gateway等)はサーバー側のフェイク、
 * DB(DataSource/Connection)だけがMockito。「何回チェックしたか」は、フェイクのgatewayが受けた
 * {@code /actuator/health}の要求数で数える(チェックのたびに1回ずつ呼ばれる)。
 */
class ConnectedServiceStatusBroadcasterTest {

    private StatusFixture fixture;
    private ConnectedServiceStatusBroadcaster broadcaster;

    @BeforeEach
    void setUp() throws Exception {
        fixture = new StatusFixture();
        DataSource dataSource = Mockito.mock(DataSource.class);
        Connection connection = Mockito.mock(Connection.class);
        Mockito.when(dataSource.getConnection()).thenReturn(connection);
        Mockito.when(connection.isValid(3)).thenReturn(true);
        broadcaster = new ConnectedServiceStatusBroadcaster(StatusServices.create(fixture, dataSource));
    }

    @AfterEach
    void tearDown() {
        fixture.close();
    }

    private int checks() {
        return fixture.http.requests("GET", "/gw/actuator/health").size();
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
