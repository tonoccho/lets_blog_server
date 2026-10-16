package com.letsblog.ai.controller;

import com.letsblog.ai.domain.GenerationJob;
import com.letsblog.ai.dto.GenerationJobDetailResponse;
import com.letsblog.ai.dto.GenerationJobResponse;
import com.letsblog.ai.service.GenerationJobNotFoundException;
import com.letsblog.ai.testsupport.AiWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GenerationJobControllerの回帰テスト。
 *
 * <p>issue #1332: generation_jobs.created_atは秒精度までしか持たないため、同一秒に作られた
 * 複数ジョブはIDを第2キー(降順)にして「新しい順」を保つ。
 *
 * <p>issue #1406: ジョブは所有者(owner_user_id)を持ち、一覧・詳細は呼び出し元の所有する
 * ジョブだけを返す。管理者は全件(所有者不明の既存行を含む)を見られる。
 *
 * <p>issue #1824: CurrentActorServiceは実物で、操作者はidentity-serviceのHTTPフェイクが返す。
 * DBはメモリ上のリポジトリで差し替える。
 */
class GenerationJobControllerTest {

    private static final long ME = 42L;
    private static final long OTHER = 43L;

    private AiWorld world;
    private GenerationJobController controller;

    @BeforeEach
    void setUp() {
        world = new AiWorld();
        controller = new GenerationJobController(world.generationJobRepository, world.currentActorService);
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    // ---- 並び順(#1332)。管理者の全件一覧で確かめる ----

    @Test
    void list_createdAtの降順で返す() {
        world.admin();
        LocalDateTime older = LocalDateTime.of(2026, 9, 21, 1, 0, 0);
        LocalDateTime newer = LocalDateTime.of(2026, 9, 21, 1, 0, 5);
        // DB取得順(ID昇順)のまま保存し、createdAtが逆順であることを検証する。
        save("plan_chat", older, ME);
        save("plan_chat", newer, ME);

        assertEquals(List.of(2L, 1L), ids(controller.list()));
    }

    @Test
    void list_同一createdAtの複数ジョブはID降順でタイブレークする() {
        world.admin();
        LocalDateTime sameSecond = LocalDateTime.of(2026, 9, 21, 1, 2, 47);
        save("plan_chat", sameSecond, ME);
        save("plan_chat", sameSecond, ME);

        assertEquals(List.of(2L, 1L), ids(controller.list()));
    }

    // ---- 所有者による絞り込み(#1406) ----

    @Test
    void list_一般利用者は自分のジョブだけを返す() {
        world.actor(ME, "user");
        LocalDateTime at = LocalDateTime.of(2026, 9, 21, 1, 0, 0);
        save("llm_draft", at, ME);
        save("llm_draft", at, OTHER);
        save("plan_chat", at, null);

        assertEquals(List.of(1L), ids(controller.list()));
    }

    @Test
    void list_管理者は所有者不明の既存行を含む全件を返す() {
        world.admin();
        LocalDateTime at = LocalDateTime.of(2026, 9, 21, 1, 0, 0);
        save("llm_draft", at, ME);
        save("llm_draft", at, OTHER);
        save("plan_chat", at, null);

        assertEquals(List.of(3L, 2L, 1L), ids(controller.list()));
    }

    @Test
    void list_操作者を解決できない一般利用者には何も返さない() {
        // Authorizationヘッダーが無いので操作者を解決できない。
        save("llm_draft", LocalDateTime.of(2026, 9, 21, 1, 0, 0), ME);

        assertTrue(controller.list().isEmpty());
    }

    @Test
    void get_自分のジョブは取得できる() {
        world.actor(ME, "user");
        save("llm_draft", LocalDateTime.now(), ME);

        GenerationJobDetailResponse response = controller.get(1L);

        assertEquals(1L, response.id());
    }

    @Test
    void get_他人のジョブは存在しないものとして扱う() {
        world.actor(ME, "user");
        save("llm_draft", LocalDateTime.now(), OTHER);

        assertThrows(GenerationJobNotFoundException.class, () -> controller.get(1L));
    }

    @Test
    void get_所有者不明の既存行は一般利用者には存在しないものとして扱う() {
        world.actor(ME, "user");
        save("plan_chat", LocalDateTime.now(), null);

        assertThrows(GenerationJobNotFoundException.class, () -> controller.get(1L));
    }

    @Test
    void get_操作者を解決できない一般利用者は存在しないものとして扱う() {
        save("plan_chat", LocalDateTime.now(), null);

        assertThrows(GenerationJobNotFoundException.class, () -> controller.get(1L));
    }

    @Test
    void get_管理者は他人のジョブも所有者不明の既存行も取得できる() {
        world.admin();
        save("llm_draft", LocalDateTime.now(), OTHER);
        save("plan_chat", LocalDateTime.now(), null);

        assertEquals(1L, controller.get(1L).id());
        assertEquals(2L, controller.get(2L).id());
    }

    @Test
    void get_存在しないジョブはGenerationJobNotFoundException() {
        assertThrows(GenerationJobNotFoundException.class, () -> controller.get(99L));
    }

    private static List<Long> ids(List<GenerationJobResponse> responses) {
        return responses.stream().map(GenerationJobResponse::id).toList();
    }

    /** メモリ上のリポジトリへ保存する(IDは1から採番される)。 */
    private void save(String type, LocalDateTime createdAt, Long ownerUserId) {
        GenerationJob job = new GenerationJob();
        job.setType(type);
        job.setStatus("done");
        job.setOwnerUserId(ownerUserId);
        job.setCreatedAt(createdAt);
        job.setUpdatedAt(createdAt);
        world.generationJobRepository.save(job);
    }
}
