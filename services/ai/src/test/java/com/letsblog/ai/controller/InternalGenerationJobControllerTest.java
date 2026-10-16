package com.letsblog.ai.controller;

import com.letsblog.ai.domain.GenerationJob;
import com.letsblog.ai.dto.CreateGenerationJobRequest;
import com.letsblog.ai.dto.GenerationJobResponse;
import com.letsblog.ai.dto.UpdateGenerationJobRequest;
import com.letsblog.ai.service.GenerationJobNotFoundException;
import com.letsblog.ai.testsupport.AiWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * InternalGenerationJobControllerの回帰テスト。#573 stage2で追加したPATCH /{id}
 * (media-serviceの非同期ジョブランナーがジョブの進捗・完了・失敗を反映するために呼ぶ)、
 * および#573 stage3で追加したPOST(legacy-apiに残らなくなったコントローラからのジョブ起動用)を
 * 検証する。
 *
 * <p>issue #830 で、この2つはコンテナ間専用でありながらgatewayのルート表に載る
 * {@code /api/generation-jobs/**} に同居していたため、{@code /api/internal/ai/generation-jobs} へ
 * 分離した。テストクラスもそれに合わせて移した(元は GenerationJobControllerTest)。
 *
 * <p>issue #1824: CurrentActorServiceは実物で、操作者はidentity-serviceのHTTPフェイクが返す。
 * DBはメモリ上のリポジトリで差し替える。
 */
class InternalGenerationJobControllerTest {

    private AiWorld world;
    private InternalGenerationJobController controller;

    @BeforeEach
    void setUp() {
        world = new AiWorld();
        controller = new InternalGenerationJobController(world.generationJobRepository, world.currentActorService);
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    private GenerationJob running(String type, Long owner) {
        GenerationJob job = new GenerationJob();
        job.setType(type);
        job.setStatus("running");
        job.setOwnerUserId(owner);
        job.setCreatedAt(LocalDateTime.now());
        job.setUpdatedAt(LocalDateTime.now());
        return world.generationJobRepository.save(job);
    }

    @Test
    void update_statusとresultPayloadを更新して保存する() {
        GenerationJob job = running("comfyui_checkpoint_download", null);

        GenerationJobResponse response =
                controller.update(job.getId(), new UpdateGenerationJobRequest("done", "{\"success\":\"true\"}"));

        assertEquals(job.getId(), response.id());
        assertEquals("done", response.status());
        assertEquals("done", world.jobRows.rows.get(job.getId()).getStatus());
        assertEquals("{\"success\":\"true\"}", world.jobRows.rows.get(job.getId()).getResultPayload());
        // 更新は2回保存される(作成時と更新時)。
        assertEquals(2, world.jobRows.saved.size());
    }

    @Test
    void update_存在しないジョブはGenerationJobNotFoundException() {
        assertThrows(GenerationJobNotFoundException.class,
                () -> controller.update(99L, new UpdateGenerationJobRequest("failed", "{}")));
        assertTrue(world.jobRows.saved.isEmpty());
    }

    @Test
    void create_type_requestPayloadを保存しstatusはrunningで返す() {
        GenerationJobResponse response = controller.create(
                new CreateGenerationJobRequest("media_garbage_collection_delete", "{\"mediaIds\":[\"1\"]}"));

        assertEquals(1L, response.id());
        assertEquals("media_garbage_collection_delete", response.type());
        assertEquals("running", response.status());
        assertNotNull(response.createdAt());

        GenerationJob saved = world.jobRows.rows.get(response.id());
        assertEquals("media_garbage_collection_delete", saved.getType());
        assertEquals("running", saved.getStatus());
        assertEquals("{\"mediaIds\":[\"1\"]}", saved.getRequestPayload());
    }

    @Test
    void create_転送されたBearerの利用者を所有者として記録する() {
        world.actor(42L, "user");

        GenerationJobResponse response =
                controller.create(new CreateGenerationJobRequest("media_garbage_collection_delete", "{}"));

        assertEquals(42L, world.jobRows.rows.get(response.id()).getOwnerUserId());
        assertEquals("Bearer t", world.http.requests("GET", "/api/identity/me").get(0).header("Authorization"));
    }

    @Test
    void create_操作者を解決できない場合は所有者不明で記録する() {
        // Authorizationヘッダーが無い(client credentialsなど)。
        GenerationJobResponse response =
                controller.create(new CreateGenerationJobRequest("media_garbage_collection_delete", "{}"));

        assertNull(world.jobRows.rows.get(response.id()).getOwnerUserId());
    }

    @Test
    void update_client_credentialsによる更新は所有者を上書きしない() {
        GenerationJob job = running("comfyui_checkpoint_download", 42L);

        controller.update(job.getId(), new UpdateGenerationJobRequest("done", "{}"));

        assertEquals(42L, world.jobRows.rows.get(job.getId()).getOwnerUserId());
        // 更新は操作者を解決しない(identity-serviceへ問い合わせない)。
        assertTrue(world.http.requests().isEmpty());
    }
}
