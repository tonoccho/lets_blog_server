package com.letsblog.ai.dto;

/**
 * ジョブの状態・結果を更新するリクエスト(#573 stage2)。media-serviceの非同期ジョブランナー
 * (ModelInstallJobRunner等)が、自身のジョブ実行の進捗・完了・失敗を反映するために呼ぶ。
 */
public record UpdateGenerationJobRequest(String status, String resultPayload) {
}
