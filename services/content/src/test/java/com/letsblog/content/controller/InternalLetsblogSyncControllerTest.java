package com.letsblog.content.controller;

import com.letsblog.content.dto.LetsblogSyncPayloadResponse;
import com.letsblog.content.service.LetsblogSyncPayloadService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/** project-service が同期のたびに最新の内容を読む内部ブリッジ(issue #1558)。 */
@ExtendWith(MockitoExtension.class)
class InternalLetsblogSyncControllerTest {

    @Mock
    private LetsblogSyncPayloadService payloadService;

    @Test
    void プロジェクトの同期内容を返す() {
        LetsblogSyncPayloadResponse response = new LetsblogSyncPayloadResponse("{\"a\":1}", "h1");
        when(payloadService.build(5L)).thenReturn(response);

        assertEquals(response, new InternalLetsblogSyncController(payloadService).payload(5L));
    }
}
