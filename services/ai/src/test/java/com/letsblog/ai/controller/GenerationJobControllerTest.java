package com.letsblog.ai.controller;

import com.letsblog.ai.domain.GenerationJob;
import com.letsblog.ai.dto.GenerationJobDetailResponse;
import com.letsblog.ai.dto.GenerationJobResponse;
import com.letsblog.ai.repository.GenerationJobRepository;
import com.letsblog.ai.service.CurrentActorService;
import com.letsblog.ai.service.GenerationJobNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GenerationJobControllerの回帰テスト。
 *
 * <p>issue #1332: generation_jobs.created_atは秒精度までしか持たないため、同一秒に作られた
 * 複数ジョブはIDを第2キー(降順)にして「新しい順」を保つ。
 *
 * <p>issue #1406: ジョブは所有者(owner_user_id)を持ち、一覧・詳細は呼び出し元の所有する
 * ジョブだけを返す。管理者は全件(所有者不明の既存行を含む)を見られる。
 */
@ExtendWith(MockitoExtension.class)
class GenerationJobControllerTest {

    private static final long ME = 42L;
    private static final long OTHER = 43L;

    @Mock
    private GenerationJobRepository generationJobRepository;

    @Mock
    private CurrentActorService currentActorService;

    private GenerationJobController controller;

    @BeforeEach
    void setUp() {
        controller = new GenerationJobController(generationJobRepository, currentActorService);
    }

    // ---- 並び順(#1332)。管理者の全件一覧で確かめる ----

    @Test
    void list_createdAtの降順で返す() {
        when(currentActorService.isAdmin()).thenReturn(true);
        LocalDateTime older = LocalDateTime.of(2026, 9, 21, 1, 0, 0);
        LocalDateTime newer = LocalDateTime.of(2026, 9, 21, 1, 0, 5);
        // findAll()はDB取得順(ID昇順)を返す想定。createdAtが逆順であることを検証する。
        when(generationJobRepository.findAll())
                .thenReturn(List.of(job(1L, "plan_chat", older, ME), job(2L, "plan_chat", newer, ME)));

        assertEquals(List.of(2L, 1L), ids(controller.list()));
    }

    @Test
    void list_同一createdAtの複数ジョブはID降順でタイブレークする() {
        when(currentActorService.isAdmin()).thenReturn(true);
        LocalDateTime sameSecond = LocalDateTime.of(2026, 9, 21, 1, 2, 47);
        when(generationJobRepository.findAll())
                .thenReturn(List.of(job(888L, "plan_chat", sameSecond, ME), job(890L, "plan_chat", sameSecond, ME)));

        assertEquals(List.of(890L, 888L), ids(controller.list()));
    }

    // ---- 所有者による絞り込み(#1406) ----

    @Test
    void list_一般利用者は自分のジョブだけを返す() {
        when(currentActorService.isAdmin()).thenReturn(false);
        when(currentActorService.getCurrentActorId()).thenReturn(ME);
        LocalDateTime at = LocalDateTime.of(2026, 9, 21, 1, 0, 0);
        when(generationJobRepository.findByOwnerUserId(ME)).thenReturn(List.of(job(1L, "llm_draft", at, ME)));

        assertEquals(List.of(1L), ids(controller.list()));
        verify(generationJobRepository, never()).findAll();
    }

    @Test
    void list_管理者は所有者不明の既存行を含む全件を返す() {
        when(currentActorService.isAdmin()).thenReturn(true);
        LocalDateTime at = LocalDateTime.of(2026, 9, 21, 1, 0, 0);
        when(generationJobRepository.findAll()).thenReturn(List.of(
                job(1L, "llm_draft", at, ME), job(2L, "llm_draft", at, OTHER), job(3L, "plan_chat", at, null)));

        assertEquals(List.of(3L, 2L, 1L), ids(controller.list()));
        verify(generationJobRepository, never()).findByOwnerUserId(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void list_操作者を解決できない一般利用者には何も返さない() {
        when(currentActorService.isAdmin()).thenReturn(false);
        when(currentActorService.getCurrentActorId()).thenReturn(null);

        assertTrue(controller.list().isEmpty());
        verify(generationJobRepository, never()).findAll();
        verify(generationJobRepository, never()).findByOwnerUserId(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void get_自分のジョブは取得できる() {
        when(currentActorService.isAdmin()).thenReturn(false);
        when(currentActorService.getCurrentActorId()).thenReturn(ME);
        when(generationJobRepository.findById(1L)).thenReturn(Optional.of(job(1L, "llm_draft", LocalDateTime.now(), ME)));

        GenerationJobDetailResponse response = controller.get(1L);

        assertEquals(1L, response.id());
    }

    @Test
    void get_他人のジョブは存在しないものとして扱う() {
        when(currentActorService.isAdmin()).thenReturn(false);
        when(currentActorService.getCurrentActorId()).thenReturn(ME);
        when(generationJobRepository.findById(2L)).thenReturn(Optional.of(job(2L, "llm_draft", LocalDateTime.now(), OTHER)));

        assertThrows(GenerationJobNotFoundException.class, () -> controller.get(2L));
    }

    @Test
    void get_所有者不明の既存行は一般利用者には存在しないものとして扱う() {
        when(currentActorService.isAdmin()).thenReturn(false);
        when(currentActorService.getCurrentActorId()).thenReturn(ME);
        when(generationJobRepository.findById(3L)).thenReturn(Optional.of(job(3L, "plan_chat", LocalDateTime.now(), null)));

        assertThrows(GenerationJobNotFoundException.class, () -> controller.get(3L));
    }

    @Test
    void get_操作者を解決できない一般利用者は存在しないものとして扱う() {
        when(currentActorService.isAdmin()).thenReturn(false);
        when(currentActorService.getCurrentActorId()).thenReturn(null);
        when(generationJobRepository.findById(3L)).thenReturn(Optional.of(job(3L, "plan_chat", LocalDateTime.now(), null)));

        assertThrows(GenerationJobNotFoundException.class, () -> controller.get(3L));
    }

    @Test
    void get_管理者は他人のジョブも所有者不明の既存行も取得できる() {
        when(currentActorService.isAdmin()).thenReturn(true);
        when(generationJobRepository.findById(2L)).thenReturn(Optional.of(job(2L, "llm_draft", LocalDateTime.now(), OTHER)));
        when(generationJobRepository.findById(3L)).thenReturn(Optional.of(job(3L, "plan_chat", LocalDateTime.now(), null)));

        assertEquals(2L, controller.get(2L).id());
        assertEquals(3L, controller.get(3L).id());
    }

    @Test
    void get_存在しないジョブはGenerationJobNotFoundException() {
        when(generationJobRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(GenerationJobNotFoundException.class, () -> controller.get(99L));
    }

    private static List<Long> ids(List<GenerationJobResponse> responses) {
        return responses.stream().map(GenerationJobResponse::id).toList();
    }

    private static GenerationJob job(Long id, String type, LocalDateTime createdAt, Long ownerUserId) {
        GenerationJob job = new GenerationJob();
        job.setId(id);
        job.setType(type);
        job.setStatus("done");
        job.setOwnerUserId(ownerUserId);
        job.setCreatedAt(createdAt);
        job.setUpdatedAt(createdAt);
        return job;
    }
}
