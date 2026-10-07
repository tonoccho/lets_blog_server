package com.letsblog.ai.dto;

/**
 * pullの開始結果(issue #1675)。{@code jobId}は進捗をポーリングするGenerationJobのID。
 * 同じプロジェクトの同じモデルがすでに実行中のときは新しく始めず、その実行中のジョブのIDを
 * {@code alreadyRunning=true}で返す。
 */
public record PullOllamaModelResponse(Long jobId, boolean alreadyRunning) {
}
