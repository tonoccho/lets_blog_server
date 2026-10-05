package com.letsblog.platform.service;

/**
 * 演算デバイスを切り替えられるサービス(issue #1399)。切り替え処理は対象を引数に取り、
 * Ollama(#1585)など別サービスは対象を足すだけで同じ機構を使える。
 *
 * @param id                サービス名(APIのパスに現れる。例: {@code comfyui})
 * @param gpuContainerName  GPU構成のコンテナ名(例: {@code lbs-comfyui})
 * @param cpuContainerName  CPU構成のコンテナ名(例: {@code lbs-comfyui-cpu})
 * @param healthUrl         切り替え後の成功判定に使うURL(HTTP 200で健全)
 */
public record ComputeTarget(String id, String gpuContainerName, String cpuContainerName, String healthUrl) {
}
