package com.letsblog.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.ai.domain.GenerationJob;
import com.letsblog.ai.dto.AiConnectionResponse.Source;
import com.letsblog.ai.dto.ProjectConnectionsResponse;
import com.letsblog.ai.dto.ProjectConnectionsResponse.Entry;
import com.letsblog.ai.dto.PullOllamaModelResponse;
import com.letsblog.ai.repository.GenerationJobRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** OllamaPullService(issue #1675)。検証・実効接続先の解決・二重開始の拒否・ジョブ作成と実行の起動。 */
@ExtendWith(MockitoExtension.class)
class OllamaPullServiceTest {

    @Mock
    private ProjectConnectionService projectConnectionService;
    @Mock
    private GenerationJobRepository generationJobRepository;
    @Mock
    private CurrentActorService currentActorService;
    @Mock
    private OllamaPullJobRunner runner;

    private OllamaPullService service;

    @BeforeEach
    void setUp() {
        service = new OllamaPullService(
                projectConnectionService, generationJobRepository, currentActorService, runner, new ObjectMapper());
    }

    private void connection(String baseUrl, Source source) {
        when(projectConnectionService.get(7L)).thenReturn(new ProjectConnectionsResponse(
                new Entry(source == Source.PROJECT ? baseUrl : null, baseUrl, source),
                new Entry(null, "http://c", Source.DATABASE)));
    }

    private void saveAssignsId(long id) {
        when(generationJobRepository.save(any(GenerationJob.class))).thenAnswer(invocation -> {
            GenerationJob job = invocation.getArgument(0);
            job.setId(id);
            return job;
        });
    }

    private static GenerationJob running(long id, String payload) {
        GenerationJob job = new GenerationJob();
        job.setId(id);
        job.setType(OllamaPullService.JOB_TYPE);
        job.setStatus("running");
        job.setRequestPayload(payload);
        return job;
    }

    @Test
    void start_プロジェクトの上書きがあればそれでジョブを作って実行を起動する() {
        connection("http://gpu:11434/v1", Source.PROJECT);
        when(currentActorService.getCurrentActorId()).thenReturn(5L);
        when(generationJobRepository.findByTypeAndStatus(OllamaPullService.JOB_TYPE, "running")).thenReturn(List.of());
        saveAssignsId(11L);

        PullOllamaModelResponse response = service.start(7L, "  qwen2.5:7b-instruct ");

        assertEquals(new PullOllamaModelResponse(11L, false), response);
        ArgumentCaptor<GenerationJob> saved = ArgumentCaptor.forClass(GenerationJob.class);
        verify(generationJobRepository).save(saved.capture());
        assertEquals(OllamaPullService.JOB_TYPE, saved.getValue().getType());
        assertEquals("running", saved.getValue().getStatus());
        assertEquals(5L, saved.getValue().getOwnerUserId());
        assertTrue(saved.getValue().getRequestPayload().contains("\"model\":\"qwen2.5:7b-instruct\""));
        assertTrue(saved.getValue().getRequestPayload().contains("\"projectId\":7"));
        verify(runner).run(11L, "http://gpu:11434/v1", true, "qwen2.5:7b-instruct");
    }

    @Test
    void start_システム設定の接続先ならプロジェクト上書きではないものとして起動する() {
        connection("http://llm-stub:8080", Source.DATABASE);
        when(generationJobRepository.findByTypeAndStatus(OllamaPullService.JOB_TYPE, "running")).thenReturn(List.of());
        saveAssignsId(12L);

        service.start(7L, "llama3");

        verify(runner).run(12L, "http://llm-stub:8080", false, "llama3");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "   ", "a b", "foo;rm -rf", "../etc", ":tag", "model:", "a:b:c", "-leading", "モデル", "x\ny", "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"})
    void start_空や不正な文字のモデル名は拒否し何も作らない(String model) {
        assertThrows(InvalidOllamaModelNameException.class, () -> service.start(7L, model));

        verifyNoInteractions(projectConnectionService, generationJobRepository, runner);
    }

    @ParameterizedTest
    @ValueSource(strings = {"llama3", "qwen2.5:7b-instruct", "hf.co/bartowski/Llama-3.2-3B-GGUF:Q4_K_M", "e2e-pull_1:latest"})
    void start_Ollamaのモデル名の形式は受け付ける(String model) {
        connection("http://o", Source.DATABASE);
        when(generationJobRepository.findByTypeAndStatus(OllamaPullService.JOB_TYPE, "running")).thenReturn(List.of());
        saveAssignsId(1L);

        service.start(7L, model);

        verify(runner).run(1L, "http://o", false, model);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    void start_Ollamaの接続先が無ければ409相当で何も作らない(String baseUrl) {
        when(projectConnectionService.get(7L)).thenReturn(new ProjectConnectionsResponse(
                new Entry(null, baseUrl, Source.NONE), new Entry(null, "http://c", Source.DATABASE)));

        assertThrows(IllegalStateException.class, () -> service.start(7L, "llama3"));

        verify(generationJobRepository, never()).save(any());
        verifyNoInteractions(runner);
    }

    @Test
    void start_同じプロジェクトの同じモデルが実行中なら新しく始めず実行中のジョブを返す() {
        connection("http://o", Source.DATABASE);
        when(generationJobRepository.findByTypeAndStatus(OllamaPullService.JOB_TYPE, "running"))
                .thenReturn(List.of(
                        running(20L, "{\"projectId\":8,\"model\":\"llama3\"}"),
                        running(21L, "{\"projectId\":7,\"model\":\"other\"}"),
                        running(22L, "not json"),
                        running(23L, null),
                        running(24L, "{\"projectId\":7,\"model\":\"llama3\"}")));

        PullOllamaModelResponse response = service.start(7L, "llama3");

        assertEquals(new PullOllamaModelResponse(24L, true), response);
        assertTrue(response.alreadyRunning());
        verify(generationJobRepository, never()).save(any());
        verifyNoInteractions(runner);
    }

    @Test
    void start_別プロジェクトや別モデルや読めないペイロードの実行中ジョブは二重開始とみなさない() {
        connection("http://o", Source.DATABASE);
        when(generationJobRepository.findByTypeAndStatus(OllamaPullService.JOB_TYPE, "running"))
                .thenReturn(List.of(
                        running(20L, "{\"projectId\":8,\"model\":\"llama3\"}"),
                        running(21L, "{\"projectId\":7,\"model\":\"other\"}"),
                        running(22L, "not json"),
                        running(23L, null)));
        saveAssignsId(30L);

        PullOllamaModelResponse response = service.start(7L, "llama3");

        assertFalse(response.alreadyRunning());
        assertEquals(30L, response.jobId());
        verify(runner).run(30L, "http://o", false, "llama3");
    }
}
