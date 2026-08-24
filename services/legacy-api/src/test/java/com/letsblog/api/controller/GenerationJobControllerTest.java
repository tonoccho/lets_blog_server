package com.letsblog.api.controller;

import com.letsblog.api.domain.GenerationJob;
import com.letsblog.api.dto.GenerationJobResponse;
import com.letsblog.api.dto.UpdateGenerationJobRequest;
import com.letsblog.api.repository.GenerationJobRepository;
import com.letsblog.api.service.GenerationJobNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * GenerationJobControllerの回帰テスト。#573 stage2で追加したPATCH /{id}
 * (media-serviceの非同期ジョブランナーがジョブの進捗・完了・失敗を反映するために呼ぶ)を検証する。
 */
@ExtendWith(MockitoExtension.class)
class GenerationJobControllerTest {

    @Mock
    private GenerationJobRepository generationJobRepository;

    private GenerationJobController controller;

    @BeforeEach
    void setUp() {
        controller = new GenerationJobController(generationJobRepository);
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
}
