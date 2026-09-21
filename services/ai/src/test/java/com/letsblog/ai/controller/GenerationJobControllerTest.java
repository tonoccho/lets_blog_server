package com.letsblog.ai.controller;

import com.letsblog.ai.domain.GenerationJob;
import com.letsblog.ai.dto.GenerationJobResponse;
import com.letsblog.ai.repository.GenerationJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/**
 * GenerationJobController#list()の回帰テスト(issue #1332)。
 *
 * <p>generation_jobs.created_atは秒精度までしか持たない(V1__create_ai_tables.sqlの
 * DATETIME列)。同一秒内に複数ジョブが作られた場合、
 * {@code Comparator.comparing(GenerationJob::getCreatedAt)}だけでは同値となり、
 * 安定ソートの結果{@code findAll()}の順(通常はID昇順、DB取得順)がそのまま残ってしまい、
 * 「新しい順」という一覧の契約が崩れる。issue #934/#1147の
 * 「Brave Search呼び出しが失敗すると、壁打ちの回答はWeb検索結果なしでフェイルオープンする」
 * シナリオが、同一秒内に作られた別ジョブを誤って拾った実例(#1332本文参照)。
 * IDを第2キー(降順)に使うことで、同一createdAtでも常に新しく作られた(ID大)ものを
 * 先に返す。
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
    void list_createdAtの降順で返す() {
        LocalDateTime older = LocalDateTime.of(2026, 9, 21, 1, 0, 0);
        LocalDateTime newer = LocalDateTime.of(2026, 9, 21, 1, 0, 5);
        GenerationJob job1 = job(1L, "plan_chat", older);
        GenerationJob job2 = job(2L, "plan_chat", newer);
        // findAll()はDB取得順(ID昇順)を返す想定。createdAtが逆順であることを検証する。
        when(generationJobRepository.findAll()).thenReturn(List.of(job1, job2));

        List<GenerationJobResponse> responses = controller.list();

        assertEquals(List.of(2L, 1L), responses.stream().map(GenerationJobResponse::id).toList());
    }

    @Test
    void list_同一createdAtの複数ジョブはID降順でタイブレークする() {
        // #1332本文の実例(id 888/890)相当: 同一秒に作られた2件。
        LocalDateTime sameSecond = LocalDateTime.of(2026, 9, 21, 1, 2, 47);
        GenerationJob earlierInsert = job(888L, "plan_chat", sameSecond);
        GenerationJob laterInsert = job(890L, "plan_chat", sameSecond);
        // findAll()はDB取得順(ID昇順)を返す想定: 888が先、890が後。
        when(generationJobRepository.findAll()).thenReturn(List.of(earlierInsert, laterInsert));

        List<GenerationJobResponse> responses = controller.list();

        assertEquals(List.of(890L, 888L), responses.stream().map(GenerationJobResponse::id).toList());
    }

    private static GenerationJob job(Long id, String type, LocalDateTime createdAt) {
        GenerationJob job = new GenerationJob();
        job.setId(id);
        job.setType(type);
        job.setStatus("done");
        job.setCreatedAt(createdAt);
        job.setUpdatedAt(createdAt);
        return job;
    }
}
