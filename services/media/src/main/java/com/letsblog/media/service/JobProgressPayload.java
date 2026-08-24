package com.letsblog.media.service;

/**
 * モデルインストール等の実行中にGenerationJob.resultPayloadへ書き込む進捗情報。
 * フロントエンドはこのJSON形状(phase/percent/bytesDone/bytesTotal)をポーリングして進捗表示に使う。
 * legacy-apiの{@code com.letsblog.api.service.JobProgressPayload}と同一の形状(#573でmedia-serviceの
 * ModelInstallJobRunnerと共に移設。legacy-api側はMediaGarbageCollectionJobRunnerが引き続き
 * 使うため複製として残る)。
 */
public record JobProgressPayload(String phase, Integer percent, Long bytesDone, Long bytesTotal) {
}
