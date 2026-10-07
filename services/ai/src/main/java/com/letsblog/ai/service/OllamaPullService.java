package com.letsblog.ai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.ai.domain.GenerationJob;
import com.letsblog.ai.dto.AiConnectionResponse.Source;
import com.letsblog.ai.dto.ProjectConnectionsResponse.Entry;
import com.letsblog.ai.dto.PullOllamaModelResponse;
import com.letsblog.ai.repository.GenerationJobRepository;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Ollamaのモデルのpullを開始する(issue #1675)。モデル名を検証し、プロジェクトの実効Ollama接続先
 * (プロジェクトの上書き → システム設定)を解決して、GenerationJobを作り、実行を
 * {@link OllamaPullJobRunner}へ渡してすぐ返る。同じプロジェクトの同じモデルがすでに実行中なら、
 * 新しく始めずその実行中のジョブを返す(二重に開始しない)。
 */
@Service
public class OllamaPullService {

    /** GenerationJobの種別。 */
    public static final String JOB_TYPE = "ollama_model_pull";
    private static final String RUNNING = "running";
    /** Ollamaのモデル名({@code name[:tag]}。{@code hf.co/user/repo:Q4_K_M}のような名前空間つきも含む)。 */
    private static final Pattern MODEL_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/\\-]*(:[A-Za-z0-9._\\-]+)?");
    private static final int MAX_MODEL_NAME_LENGTH = 200;

    private final ProjectConnectionService projectConnectionService;
    private final GenerationJobRepository generationJobRepository;
    private final CurrentActorService currentActorService;
    private final OllamaPullJobRunner runner;
    private final ObjectMapper objectMapper;

    public OllamaPullService(
            ProjectConnectionService projectConnectionService, GenerationJobRepository generationJobRepository,
            CurrentActorService currentActorService, OllamaPullJobRunner runner, ObjectMapper objectMapper) {
        this.projectConnectionService = projectConnectionService;
        this.generationJobRepository = generationJobRepository;
        this.currentActorService = currentActorService;
        this.runner = runner;
        this.objectMapper = objectMapper;
    }

    public PullOllamaModelResponse start(Long projectId, String requestedModel) {
        String model = validate(requestedModel);
        Entry ollama = projectConnectionService.get(projectId).ollama();
        String baseUrl = ollama.baseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("Ollamaの接続先が設定されていません。先に接続先を設定してください");
        }
        Long running = findRunningJob(projectId, model);
        if (running != null) {
            return new PullOllamaModelResponse(running, true);
        }
        GenerationJob job = new GenerationJob();
        job.setType(JOB_TYPE);
        job.setStatus(RUNNING);
        job.setRequestPayload(requestPayload(projectId, model));
        job.setOwnerUserId(currentActorService.getCurrentActorId());
        Long jobId = generationJobRepository.save(job).getId();
        runner.run(jobId, baseUrl, ollama.source() == Source.PROJECT, model);
        return new PullOllamaModelResponse(jobId, false);
    }

    private static String validate(String model) {
        String trimmed = model == null ? "" : model.strip();
        if (trimmed.isEmpty()) {
            throw new InvalidOllamaModelNameException("モデル名を入力してください");
        }
        if (trimmed.length() > MAX_MODEL_NAME_LENGTH || !MODEL_NAME.matcher(trimmed).matches()) {
            throw new InvalidOllamaModelNameException(
                    "モデル名の形式が不正です(例: qwen2.5:7b-instruct)。英数字と . _ - / と、タグの区切りの : だけが使えます");
        }
        return trimmed;
    }

    /** 同じプロジェクトの同じモデルを実行中のジョブのID。無ければnull。読めない要求ペイロードのジョブは無視する。 */
    private Long findRunningJob(Long projectId, String model) {
        for (GenerationJob job : generationJobRepository.findByTypeAndStatus(JOB_TYPE, RUNNING)) {
            if (isFor(job, projectId, model)) {
                return job.getId();
            }
        }
        return null;
    }

    private boolean isFor(GenerationJob job, Long projectId, String model) {
        if (job.getRequestPayload() == null) {
            return false;
        }
        try {
            JsonNode payload = objectMapper.readTree(job.getRequestPayload());
            return payload.path("projectId").asLong(-1) == projectId && model.equals(payload.path("model").asText());
        } catch (JsonProcessingException e) {
            return false;
        }
    }

    private String requestPayload(Long projectId, String model) {
        return objectMapper.createObjectNode().put("projectId", projectId).put("model", model).toString();
    }
}
