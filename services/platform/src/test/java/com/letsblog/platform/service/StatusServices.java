package com.letsblog.platform.service;

import com.letsblog.common.auth.ToolsTokenProvider;
import com.letsblog.platform.testsupport.StatusFixture;
import javax.sql.DataSource;
import org.springframework.web.client.RestClient;

/**
 * 連携状況のテストが使う、実物の{@link ConnectedServiceStatusService}の組み立て(issue #1825)。
 * 設定サービス・gateway・RabbitMQ・Penpot・GitLabは{@link StatusFixture}の実物。ComfyUI・PlantUML・
 * WordPress・Ollamaの接続先は、既定では接続できないアドレス(ポート1)を向ける。
 */
public final class StatusServices {

    public static final String CLOSED = "http://127.0.0.1:1";

    private StatusServices() {
    }

    public static ConnectedServiceStatusService create(StatusFixture fixture, DataSource dataSource) {
        return create(fixture, dataSource, ToolsTokenProvider.disabled());
    }

    public static ConnectedServiceStatusService create(
            StatusFixture fixture, DataSource dataSource, ToolsTokenProvider tools) {
        return new ConnectedServiceStatusService(
                dataSource,
                baseUrl -> RestClient.builder().baseUrl(baseUrl),
                RestClient.builder().baseUrl(CLOSED), CLOSED,
                RestClient.builder().baseUrl(CLOSED), CLOSED,
                fixture.systemSettingService, fixture.appSettingService,
                fixture.letsBlogServiceStatusService, fixture.rabbitMqQueueStatusService,
                baseUrl -> RestClient.builder().baseUrl(baseUrl),
                fixture.penpotChecker, fixture.gitLabChecker, tools);
    }

    /** docker-socket-proxy(HTTP)のベースURLを指す、実物の{@link ContainerStatusService}。 */
    public static ContainerStatusService containerStatusService(String dockerBaseUrl) {
        return new ContainerStatusService(RestClient.builder().baseUrl(dockerBaseUrl), "");
    }
}
