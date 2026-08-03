package com.letsblog.api.service;

/**
 * モデルインストール等の実行中にGenerationJob.resultPayloadへ書き込む進捗情報。
 * フロントエンドはこのJSON形状(phase/percent/bytesDone/bytesTotal)をポーリングして進捗表示に使う。
 */
public record JobProgressPayload(String phase, Integer percent, Long bytesDone, Long bytesTotal) {
}
