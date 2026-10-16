package com.letsblog.content.controller;

import com.letsblog.content.dto.LetsblogSyncPayloadResponse;
import com.letsblog.content.testsupport.ContentWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * project-service が同期のたびに最新の内容を読む内部ブリッジ(issue #1558)。
 * コントローラ・{@code LetsblogSyncPayloadService}・各サービスは実物で、差し替えるのは外部との通信だけ。
 */
class InternalLetsblogSyncControllerTest {

    private ContentWorld world;

    @BeforeEach
    void setUp() {
        world = new ContentWorld().actingAsAdmin(1L);
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    @Test
    void プロジェクトの同期内容と_その内容のSHA256を返す() {
        LetsblogSyncPayloadResponse response =
                new InternalLetsblogSyncController(world.letsblogSyncPayloadService).payload(5L);

        assertEquals(5L, world.parse(response.payload()).get("projectId").asLong());
        assertEquals(ContentWorld.sha256(response.payload()), response.hash());
    }
}
