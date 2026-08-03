package com.letsblog.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.api.ai.OllamaClient;
import com.letsblog.api.domain.GenerationJob;
import com.letsblog.api.dto.PlanChatMessage;
import com.letsblog.api.dto.PlanChatResponse;
import com.letsblog.api.dto.SuggestTitlesResponse;
import com.letsblog.api.repository.GenerationJobRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ArticlePlanServiceTest {

    @Mock
    private OllamaClient ollamaClient;

    @Mock
    private GenerationJobRepository generationJobRepository;

    // GenerationJob はミュータブルなため、save() 呼び出しの都度その時点のstatusをスナップショットして記録する
    private final List<String> savedStatuses = new ArrayList<>();

    private ArticlePlanService service() {
        when(generationJobRepository.save(any(GenerationJob.class))).thenAnswer(invocation -> {
            GenerationJob job = invocation.getArgument(0);
            savedStatuses.add(job.getStatus());
            return job;
        });
        return new ArticlePlanService(ollamaClient, generationJobRepository, new ObjectMapper());
    }

    @Test
    void chat_履歴とメッセージを含むプロンプトを組み立ててOllamaを呼び出す() {
        ArticlePlanService service = service();
        List<PlanChatMessage> history = List.of(
                new PlanChatMessage("user", "AIブログの企画を考えたい"),
                new PlanChatMessage("assistant", "どんな読者層を想定していますか?")
        );
        when(ollamaClient.generate(anyString())).thenReturn("初心者エンジニア向けはどうでしょう");

        PlanChatResponse response = service.chat(1L, history, "初心者向けにしたいです");

        assertEquals("初心者エンジニア向けはどうでしょう", response.reply());

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(ollamaClient).generate(promptCaptor.capture());
        String prompt = promptCaptor.getValue();
        assertTrue(prompt.contains("AIブログの企画を考えたい"));
        assertTrue(prompt.contains("どんな読者層を想定していますか?"));
        assertTrue(prompt.contains("初心者向けにしたいです"));
        assertTrue(prompt.startsWith("System:"));
    }

    @Test
    void chat_生成ジョブがplan_chatとして記録される() {
        ArticlePlanService service = service();
        when(ollamaClient.generate(anyString())).thenReturn("応答");

        service.chat(1L, List.of(), "テーマ");

        assertEquals(List.of("running", "done"), savedStatuses);

        ArgumentCaptor<GenerationJob> jobCaptor = ArgumentCaptor.forClass(GenerationJob.class);
        verify(generationJobRepository, org.mockito.Mockito.atLeastOnce()).save(jobCaptor.capture());
        assertEquals("plan_chat", jobCaptor.getValue().getType());
    }

    @Test
    void chat_Ollama呼び出しが失敗した場合ジョブがfailedになり例外を再送出する() {
        ArticlePlanService service = service();
        when(ollamaClient.generate(anyString())).thenThrow(new RuntimeException("接続できません"));

        assertThrows(RuntimeException.class, () -> service.chat(1L, List.of(), "テーマ"));

        assertEquals(List.of("running", "failed"), savedStatuses);
    }

    @Test
    void suggestTitles_タイトル提案の指示がプロンプトに含まれる() {
        ArticlePlanService service = service();
        when(ollamaClient.generate(anyString())).thenReturn("[\"タイトル1\", \"タイトル2\"]");

        service.suggestTitles(1L, List.of(new PlanChatMessage("user", "テーマ案")));

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(ollamaClient).generate(promptCaptor.capture());
        assertTrue(promptCaptor.getValue().contains("JSON配列形式"));
    }

    @Test
    void suggestTitles_JSON配列を正しくパースする() {
        ArticlePlanService service = service();
        when(ollamaClient.generate(anyString())).thenReturn("[\"タイトル1\", \"タイトル2\"]");

        SuggestTitlesResponse response = service.suggestTitles(1L, List.of());

        assertEquals(List.of("タイトル1", "タイトル2"), response.titles());
    }

    @Test
    void suggestTitles_前後に説明文が付いていてもJSON配列部分だけを抽出する() {
        ArticlePlanService service = service();
        when(ollamaClient.generate(anyString()))
                .thenReturn("以下が提案です。\n[\"タイトルA\", \"タイトルB\"]\nご確認ください。");

        SuggestTitlesResponse response = service.suggestTitles(1L, List.of());

        assertEquals(List.of("タイトルA", "タイトルB"), response.titles());
    }

    @Test
    void suggestTitles_不正なJSONの場合は空リストを返す() {
        ArticlePlanService service = service();
        when(ollamaClient.generate(anyString())).thenReturn("JSONではない応答です");

        SuggestTitlesResponse response = service.suggestTitles(1L, List.of());

        assertEquals(List.of(), response.titles());
    }

    @Test
    void suggestTitles_6件以上の提案は5件に制限される() {
        ArticlePlanService service = service();
        when(ollamaClient.generate(anyString()))
                .thenReturn("[\"1\", \"2\", \"3\", \"4\", \"5\", \"6\", \"7\"]");

        SuggestTitlesResponse response = service.suggestTitles(1L, List.of());

        assertEquals(5, response.titles().size());
    }

    @Test
    void suggestTitles_生成ジョブがplan_suggest_titlesとして記録される() {
        ArticlePlanService service = service();
        when(ollamaClient.generate(anyString())).thenReturn("[]");

        service.suggestTitles(1L, List.of());

        assertEquals(List.of("running", "done"), savedStatuses);

        ArgumentCaptor<GenerationJob> jobCaptor = ArgumentCaptor.forClass(GenerationJob.class);
        verify(generationJobRepository, org.mockito.Mockito.atLeastOnce()).save(jobCaptor.capture());
        assertEquals("plan_suggest_titles", jobCaptor.getValue().getType());
    }
}
