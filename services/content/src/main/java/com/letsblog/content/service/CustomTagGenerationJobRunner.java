package com.letsblog.content.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.common.client.GenerationJobClient;
import com.letsblog.content.client.AiServiceException;
import com.letsblog.content.dto.GenerateCustomTagRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * カスタムタグ生成ジョブ(issue #1409)の実行本体。受理側の{@link CustomTagGenerationJobStarter}とは
 * {@code @Async}の自己呼び出し問題のため別クラス。完了・失敗は{@link GenerationJobClient#updateStatus}
 * (サービス自身のClient Credentials。#1083)でジョブへ反映するので、呼び出し元ユーザーのBearerは持たない。
 *
 * <p><b>生成結果は{@code custom_tags}へ書かない。</b>生成したHTML/CSSはジョブの結果
 * ({@code generation_jobs.result_payload})にだけ載せる。利用者が処理キューの「結果を見る」から確認して
 * 「保存」を押したときに初めて、既存の{@code POST /api/custom-tags}が書く。したがってこのクラスは
 * 保存先のリポジトリを持たない。LLMの応答時間はai-serviceのタイムアウト(既定120秒)に収まり、
 * ai-serviceの滞留回収(既定15分)にも収まるので、ハートビートは持たない。
 *
 * <p>失敗には利用者が読める{@code error}と原因を区別できる{@code errorType}を残す:
 * {@value #ERROR_INVALID_CONTENT} / {@value #ERROR_AI_UNAVAILABLE} / {@value #ERROR_GENERATION_FAILED}。
 */
@Service
public class CustomTagGenerationJobRunner {

    static final String ERROR_INVALID_CONTENT = "invalid_content";
    static final String ERROR_AI_UNAVAILABLE = "ai_unavailable";
    static final String ERROR_GENERATION_FAILED = "generation_failed";

    private static final Logger log = LoggerFactory.getLogger(CustomTagGenerationJobRunner.class);

    private final CustomTagGenerationService generationService;
    private final GenerationJobClient generationJobClient;
    private final ObjectMapper objectMapper;

    public CustomTagGenerationJobRunner(
            CustomTagGenerationService generationService,
            GenerationJobClient generationJobClient,
            ObjectMapper objectMapper) {
        this.generationService = generationService;
        this.generationJobClient = generationJobClient;
        this.objectMapper = objectMapper;
    }

    @Async("customTagGenerationExecutor")
    public void run(Long jobId, GenerateCustomTagRequest request) {
        try {
            CustomTagGenerationService.GeneratedContent content = generationService.generateContent(request.prompt());
            generationJobClient.updateStatus(jobId, "done", toJson(donePayload(request, content)));
        } catch (RuntimeException e) {
            log.warn("Custom tag generation job {} failed", jobId, e);
            generationJobClient.updateStatus(jobId, "failed", toJson(failurePayload(e)));
        }
    }

    private static Map<String, Object> donePayload(
            GenerateCustomTagRequest request, CustomTagGenerationService.GeneratedContent content) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tagName", request.tagName());
        result.put("description", request.description());
        result.put("projectId", request.projectId());
        result.put("htmlTemplate", content.htmlTemplate());
        result.put("cssContent", content.cssContent());
        return result;
    }

    static Map<String, Object> failurePayload(RuntimeException e) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        result.put("errorType", classify(e));
        return result;
    }

    private static String classify(RuntimeException e) {
        if (e instanceof InvalidCustomTagContentException) {
            return ERROR_INVALID_CONTENT;
        }
        if (e instanceof AiServiceException) {
            return ERROR_AI_UNAVAILABLE;
        }
        return ERROR_GENERATION_FAILED;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
