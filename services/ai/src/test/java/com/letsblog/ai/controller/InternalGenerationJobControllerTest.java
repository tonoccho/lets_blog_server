package com.letsblog.ai.controller;

import com.letsblog.ai.domain.GenerationJob;
import com.letsblog.ai.dto.CreateGenerationJobRequest;
import com.letsblog.ai.dto.GenerationJobResponse;
import com.letsblog.ai.dto.UpdateGenerationJobRequest;
import com.letsblog.ai.repository.GenerationJobRepository;
import com.letsblog.ai.service.GenerationJobNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * InternalGenerationJobControllerの回帰テスト。#573 stage2で追加したPATCH /{id}
 * (media-serviceの非同期ジョブランナーがジョブの進捗・完了・失敗を反映するために呼ぶ)、
 * および#573 stage3で追加したPOST(legacy-apiに残らなくなったコントローラからのジョブ起動用)を
 * 検証する。
 *
 * <p>issue #830 で、この2つはコンテナ間専用でありながらgatewayのルート表に載る
 * {@code /api/generation-jobs/**} に同居していたため、{@code /api/internal/ai/generation-jobs} へ
 * 分離した。テストクラスもそれに合わせて移した(元は GenerationJobControllerTest)。
 */
@ExtendWith(MockitoExtension.class)
class InternalGenerationJobControllerTest {

    @Mock
    private GenerationJobRepository generationJobRepository;

    private InternalGenerationJobController controller;

    @BeforeEach
    void setUp() {
        controller = new InternalGenerationJobController(generationJobRepository);
    }

    @Test
    void update_statusとresultPayloadを更新して保存する() {
        GenerationJob job = new GenerationJob();
        job.setId(5L);
        job.setType("comfyui_checkpoint_download");
        job.setStatus("running");
        job.setCreatedAt(LocalDateTime.now());
        job.setUpdatedAt(LocalDateTime.now());

        when(generationJobRepository.findById(5L)).thenReturn(Optional.of(job));
        when(generationJobRepository.save(any(GenerationJob.class))).thenAnswer(invocation -> invocation.getArgument(0));

        GenerationJobResponse response = controller.update(5L, new UpdateGenerationJobRequest("done", "{\"success\":\"true\"}"));

        assertEquals(5L, response.id());
        assertEquals("done", response.status());
        assertEquals("done", job.getStatus());
        assertEquals("{\"success\":\"true\"}", job.getResultPayload());
    }

    @Test
    void update_存在しないジョブはGenerationJobNotFoundException() {
        when(generationJobRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(GenerationJobNotFoundException.class,
                () -> controller.update(99L, new UpdateGenerationJobRequest("failed", "{}")));
    }

    @Test
    void create_type_requestPayloadを保存しstatusはrunningで返す() {
        when(generationJobRepository.save(any(GenerationJob.class))).thenAnswer(invocation -> {
            GenerationJob job = invocation.getArgument(0);
            job.setId(10L);
            job.setCreatedAt(LocalDateTime.now());
            job.setUpdatedAt(LocalDateTime.now());
            return job;
        });

        GenerationJobResponse response = controller.create(
                new CreateGenerationJobRequest("media_garbage_collection_delete", "{\"mediaIds\":[\"1\"]}"));

        assertEquals(10L, response.id());
        assertEquals("media_garbage_collection_delete", response.type());
        assertEquals("running", response.status());
        assertNotNull(response.createdAt());

        ArgumentCaptor<GenerationJob> savedJob = ArgumentCaptor.forClass(GenerationJob.class);
        verify(generationJobRepository).save(savedJob.capture());
        assertEquals("media_garbage_collection_delete", savedJob.getValue().getType());
        assertEquals("running", savedJob.getValue().getStatus());
        assertEquals("{\"mediaIds\":[\"1\"]}", savedJob.getValue().getRequestPayload());
    }
}
