package com.letsblog.project.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.common.client.GenerationJobClient;
import com.letsblog.project.client.AiServiceException;
import com.letsblog.project.client.BearerScope;
import com.letsblog.project.domain.EmbedTagType;
import com.letsblog.project.domain.StaticContentType;
import com.letsblog.project.dto.GenerateTagDesignResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 静的コンテンツ・タグデザインのAI生成ジョブ(issue #1409)の実行本体。受理側の
 * {@link TextGenerationJobStarter}とは{@code @Async}の自己呼び出し問題のため別クラス。完了・失敗は
 * {@link GenerationJobClient#updateStatus}(サービス自身のClient Credentials。#1083)でジョブへ反映するので、
 * 呼び出し元ユーザーのBearerは持たない(静的コンテンツのプラグイン取得ブリッジにだけ、受理時のBearerを
 * {@link BearerScope}で取り置いて使う。#1558と同じ)。
 *
 * <p><b>生成結果は保存先({@code static_content}・{@code tag_design_settings})へ書かない。</b>生成した本文・
 * CSS/HTMLはジョブの結果({@code generation_jobs.result_payload})にだけ載せる。利用者が処理キューの
 * 「結果を見る」から確認して「保存」を押したときに初めて、既存の保存API
 * ({@code PUT .../static-content/{type}}・{@code PUT .../tag-design-settings/{tagType}})が書く。
 * LLMの応答時間はai-serviceのタイムアウト(既定120秒)に収まり、ai-serviceの滞留回収(既定15分)にも
 * 収まるので、ハートビートは持たない。
 *
 * <p>失敗には利用者が読める{@code error}と原因を区別できる{@code errorType}を残す:
 * {@value #ERROR_SITE_NOT_FOUND} / {@value #ERROR_AI_UNAVAILABLE} / {@value #ERROR_GENERATION_FAILED} /
 * {@value #ERROR_INVALID_CONTENT} / {@value #ERROR_UNEXPECTED}。
 */
@Service
public class TextGenerationJobRunner {

    static final String ERROR_SITE_NOT_FOUND = "site_not_found";
    static final String ERROR_AI_UNAVAILABLE = "ai_unavailable";
    static final String ERROR_GENERATION_FAILED = "generation_failed";
    static final String ERROR_INVALID_CONTENT = "invalid_content";
    static final String ERROR_UNEXPECTED = "unexpected_error";

    private static final Logger log = LoggerFactory.getLogger(TextGenerationJobRunner.class);

    private final StaticContentGenerationService staticContentGenerationService;
    private final TagDesignGenerationService tagDesignGenerationService;
    private final TagDesignSettingService tagDesignSettingService;
    private final GenerationJobClient generationJobClient;
    private final ObjectMapper objectMapper;

    public TextGenerationJobRunner(
            StaticContentGenerationService staticContentGenerationService,
            TagDesignGenerationService tagDesignGenerationService,
            TagDesignSettingService tagDesignSettingService,
            GenerationJobClient generationJobClient,
            ObjectMapper objectMapper) {
        this.staticContentGenerationService = staticContentGenerationService;
        this.tagDesignGenerationService = tagDesignGenerationService;
        this.tagDesignSettingService = tagDesignSettingService;
        this.generationJobClient = generationJobClient;
        this.objectMapper = objectMapper;
    }

    /** @param bearer 受理側が取り置いた呼び出し元のBearer。サービス間ブリッジ(プラグイン取得)が使う。 */
    @Async("textGenerationExecutor")
    public void runStaticContent(Long jobId, Long siteId, StaticContentType contentType, String bearer) {
        try {
            String body = BearerScope.call(bearer, () -> staticContentGenerationService.generateBody(siteId, contentType));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("siteId", siteId);
            result.put("contentType", contentType.name());
            result.put("body", body);
            generationJobClient.updateStatus(jobId, "done", toJson(result));
        } catch (RuntimeException e) {
            fail(jobId, "Static content generation", e);
        }
    }

    @Async("textGenerationExecutor")
    public void runTagDesign(Long jobId, Long projectId, EmbedTagType tagType, String prompt) {
        try {
            String currentHtmlTemplate = tagDesignSettingService.resolveHtmlTemplate(projectId, tagType);
            GenerateTagDesignResponse generated =
                    tagDesignGenerationService.generate(projectId, tagType, prompt, currentHtmlTemplate);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("projectId", projectId);
            result.put("tagType", tagType.name());
            result.put("htmlTemplate", generated.htmlTemplate());
            result.put("cssContent", generated.cssContent());
            generationJobClient.updateStatus(jobId, "done", toJson(result));
        } catch (RuntimeException e) {
            fail(jobId, "Tag design generation", e);
        }
    }

    private void fail(Long jobId, String what, RuntimeException e) {
        log.warn("{} job {} failed", what, jobId, e);
        generationJobClient.updateStatus(jobId, "failed", toJson(failurePayload(e)));
    }

    static Map<String, Object> failurePayload(RuntimeException e) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        result.put("errorType", classify(e));
        return result;
    }

    private static String classify(RuntimeException e) {
        if (e instanceof SiteNotFoundException) {
            return ERROR_SITE_NOT_FOUND;
        }
        if (e instanceof AiServiceException) {
            return ERROR_AI_UNAVAILABLE;
        }
        if (e instanceof AiServiceGenerationException) {
            return ERROR_GENERATION_FAILED;
        }
        if (e instanceof InvalidCustomTagContentException) {
            return ERROR_INVALID_CONTENT;
        }
        return ERROR_UNEXPECTED;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
