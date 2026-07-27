package com.letsblog.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.api.ai.OllamaClient;
import com.letsblog.api.domain.GenerationJob;
import com.letsblog.api.dto.AiDraftRequest;
import com.letsblog.api.dto.AiDraftResponse;
import com.letsblog.api.dto.AiTagsRequest;
import com.letsblog.api.dto.AiTagsResponse;
import com.letsblog.api.repository.GenerationJobRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Ollamaを利用した記事執筆支援(下書き/校正/要約、タグ・カテゴリ提案)。
 * 呼び出しごとに generation_jobs テーブルへ履歴を記録する。
 */
@Service
public class AiAssistService {

    private static final Map<String, String> DRAFT_PROMPT_TEMPLATES = Map.of(
            "draft", """
                    あなたはブログ執筆アシスタントです。以下のお題から、日本語のブログ記事の下書きをMarkdown形式で作成してください。
                    見出し(#, ##)を使い、簡潔で読みやすい文章にしてください。前置きや説明は書かず、記事本文だけを出力してください。

                    お題:
                    %s
                    """,
            "proofread", """
                    あなたは日本語のプロの校正者です。以下の文章の誤字脱字・文法・表現を校正し、Markdown形式のまま校正後の全文だけを出力してください。
                    説明や前置きは不要です。

                    本文:
                    %s
                    """,
            "summarize", """
                    あなたはブログ編集者です。以下の記事本文を、日本語で3〜5行程度に要約してください。要約文だけを出力してください。

                    本文:
                    %s
                    """
    );

    private static final String TAGS_PROMPT_TEMPLATE = """
            以下のブログ記事本文を読み、適切なカテゴリ候補とタグ候補を提案してください。
            出力は必ず次のJSON形式のみとし、他の文章は一切含めないでください。

            {"categories": ["カテゴリ1", "カテゴリ2"], "tags": ["タグ1", "タグ2", "タグ3"]}

            本文:
            %s
            """;

    private final OllamaClient ollamaClient;
    private final GenerationJobRepository generationJobRepository;
    private final ObjectMapper objectMapper;

    public AiAssistService(OllamaClient ollamaClient, GenerationJobRepository generationJobRepository,
                           ObjectMapper objectMapper) {
        this.ollamaClient = ollamaClient;
        this.generationJobRepository = generationJobRepository;
        this.objectMapper = objectMapper;
    }

    public AiDraftResponse draft(AiDraftRequest request) {
        String template = DRAFT_PROMPT_TEMPLATES.get(request.mode());
        if (template == null) {
            throw new IllegalArgumentException(
                    "mode は draft/proofread/summarize のいずれかを指定してください: " + request.mode());
        }

        GenerationJob job = startJob("ollama_" + request.mode(), Map.of("mode", request.mode(), "text", request.text()));
        try {
            String result = ollamaClient.generate(template.formatted(request.text()));
            completeJob(job, Map.of("result", result));
            return new AiDraftResponse(result);
        } catch (RuntimeException e) {
            failJob(job, e);
            throw e;
        }
    }

    public AiTagsResponse suggestTags(AiTagsRequest request) {
        GenerationJob job = startJob("ollama_tags", Map.of("text", request.text()));
        try {
            String raw = ollamaClient.generate(TAGS_PROMPT_TEMPLATE.formatted(request.text()));
            AiTagsResponse parsed = parseTagsResponse(raw);
            completeJob(job, Map.of("result", raw));
            return parsed;
        } catch (RuntimeException e) {
            failJob(job, e);
            throw e;
        }
    }

    private AiTagsResponse parseTagsResponse(String raw) {
        String jsonPart = extractJsonObject(raw);
        try {
            JsonNode node = objectMapper.readTree(jsonPart);
            List<String> categories = toStringList(node.get("categories"));
            List<String> tags = toStringList(node.get("tags"));
            return new AiTagsResponse(categories, tags);
        } catch (Exception e) {
            return new AiTagsResponse(List.of(), List.of());
        }
    }

    private String extractJsonObject(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end < 0 || end < start) {
            return raw;
        }
        return raw.substring(start, end + 1);
    }

    private List<String> toStringList(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(n -> result.add(n.asText()));
        }
        return result;
    }

    private GenerationJob startJob(String type, Map<String, String> requestPayload) {
        GenerationJob job = new GenerationJob();
        job.setType(type);
        job.setStatus("running");
        job.setRequestPayload(toJson(requestPayload));
        return generationJobRepository.save(job);
    }

    private void completeJob(GenerationJob job, Map<String, String> resultPayload) {
        job.setStatus("done");
        job.setResultPayload(toJson(resultPayload));
        generationJobRepository.save(job);
    }

    private void failJob(GenerationJob job, Exception e) {
        job.setStatus("failed");
        job.setResultPayload(toJson(Map.of("error", String.valueOf(e.getMessage()))));
        generationJobRepository.save(job);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
