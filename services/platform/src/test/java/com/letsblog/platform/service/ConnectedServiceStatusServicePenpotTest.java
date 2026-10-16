package com.letsblog.platform.service;

import com.letsblog.platform.dto.ConnectedServiceStatusDetailResponse;
import com.letsblog.platform.dto.ConnectedServiceStatusResponse;
import com.letsblog.platform.dto.ConnectedServiceStatusResponse.Status;
import com.letsblog.platform.testsupport.FakeHttpServer;
import com.letsblog.platform.testsupport.StatusFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 連携サービスの状況のPenpot行(issue #1774)。接続先はDB(AppSettingService)で解決し、未設定は警告、
 * 到達できればNORMAL、できなければERRORと理由。他の行の判定は変えない。
 *
 * <p>issue #1825: 設定サービス・Penpotのチェッカー・gateway/RabbitMQ管理APIの判定はすべて実物で、
 * HTTPはサーバー側のフェイク、DB(DataSource/Connection)だけがMockitoの差し替え。
 */
class ConnectedServiceStatusServicePenpotTest {

    private static final String PENPOT = "http://penpot.lan:9001";

    private StatusFixture fixture;
    private ConnectedServiceStatusService service;

    @BeforeEach
    void setUp() throws Exception {
        fixture = new StatusFixture();
        DataSource dataSource = Mockito.mock(DataSource.class);
        Connection connection = Mockito.mock(Connection.class);
        Mockito.when(dataSource.getConnection()).thenReturn(connection);
        Mockito.when(connection.isValid(3)).thenReturn(true);
        fixture.braveKey(true);
        service = StatusServices.create(fixture, dataSource);
    }

    @AfterEach
    void tearDown() {
        fixture.close();
    }

    private ConnectedServiceStatusDetailResponse penpotRow() {
        return service.checkAllDetailed().stream()
                .filter(r -> r.id().equals("penpot")).findFirst().orElseThrow();
    }

    @Test
    void 未設定なら接続を試みず未設定と分かる警告になる() {
        ConnectedServiceStatusDetailResponse row = penpotRow();

        assertEquals("Penpot", row.name());
        assertEquals(Status.WARNING, row.status());
        assertEquals("未設定(システム設定で設定してください)", row.errorMessage());
        assertNull(row.targetUrl());
        assertTrue(fixture.http.requests("GET", "/penpot").isEmpty());
    }

    @Test
    void 到達できればNORMAL() {
        fixture.setting("penpot_base_url", PENPOT);

        ConnectedServiceStatusDetailResponse row = penpotRow();

        assertEquals(Status.NORMAL, row.status());
        assertEquals(200, row.httpStatus());
        assertNull(row.errorMessage());
        assertEquals(PENPOT + "/", row.targetUrl());
        assertEquals(1, fixture.http.requests("GET", "/penpot").size());
    }

    @Test
    void 到達できなければERRORで理由が出る() {
        // ループバックは接続先の検査で拒否されるので、接続せずにERRORと理由になる。
        fixture.setting("penpot_base_url", "http://127.0.0.1:9001");

        ConnectedServiceStatusDetailResponse row = penpotRow();

        assertEquals(Status.ERROR, row.status());
        assertEquals("http://127.0.0.1:9001/", row.targetUrl());
        assertFalse(row.errorMessage().isBlank());
        assertTrue(fixture.http.requests("GET", "/penpot").isEmpty());
    }

    @Test
    void 簡易な一覧にもPenpotの行が含まれ他の行の判定はPenpotの結果に左右されない() {
        fixture.setting("penpot_base_url", "http://127.0.0.1:9001");
        fixture.http.on("GET", "/penpot", FakeHttpServer.Response.json(503, "{}"));

        List<ConnectedServiceStatusResponse> rows = service.checkAll();

        assertTrue(rows.stream().anyMatch(r -> r.id().equals("penpot") && r.status() == Status.ERROR));
        String others = rows.stream().filter(r -> !r.id().equals("penpot") && !r.id().endsWith("Service"))
                .map(r -> r.id() + "=" + r.status()).collect(Collectors.joining(","));
        assertEquals("database=NORMAL,llm=NORMAL,comfyui=WARNING,plantuml=ERROR,"
                + "wordpress-provisioning=ERROR,gitlab=WARNING,brave-search=NORMAL,rabbitmq-queues=NORMAL", others);
        assertFalse(others.contains("penpot"));
    }

    @Test
    void Penpotが5xxを返せばWARNINGになる() {
        fixture.setting("penpot_base_url", PENPOT);
        fixture.http.on("GET", "/penpot", FakeHttpServer.Response.json(503, "{}"));

        assertEquals(Status.WARNING, penpotRow().status());
    }
}
