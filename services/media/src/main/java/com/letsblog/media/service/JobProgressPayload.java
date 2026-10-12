package com.letsblog.media.service;

/**
 * 画像生成・メディアGC等の実行中にGenerationJob.resultPayloadへ書き込む進捗情報。
 * フロントエンドはこのJSON形状(phase/percent/bytesDone/bytesTotal)をポーリングして進捗表示に使う。
 * legacy-apiの{@code com.letsblog.api.service.JobProgressPayload}と同一の形状(#573で移設)。
 */
public record JobProgressPayload(String phase, Integer percent, Long bytesDone, Long bytesTotal) {
}
