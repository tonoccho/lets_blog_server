package com.letsblog.platform.service;

/**
 * 演算デバイスを切り替えられるサービス(issue #1399)。切り替え処理は対象を引数に取り、
 * Ollama(#1585)など別サービスは対象を足すだけで同じ機構を使える。
 *
 * @param id                サービス名(APIのパスに現れる。例: {@code comfyui})
 * @param displayName       利用者向けの名前(メッセージに使う。例: {@code ComfyUI})
 * @param gpuContainerName  GPU構成のコンテナ名(例: {@code lbs-comfyui})
 * @param cpuContainerName  CPU構成のコンテナ名(例: {@code lbs-comfyui-cpu})
 * @param healthUrl         切り替え後の成功判定に使うURL(HTTP 200で健全)。nullなら、目的のコンテナの
 *                          ヘルスチェックが{@code healthy}であることで判定する(Ollama。イメージにcurlが無い)
 * @param gpuByRuntime      GPU構成の有無を、GPU構成のコンテナの{@code HostConfig.Runtime}が{@code nvidia}か
 *                          で判定するか。falseなら、コンテナが存在するかで判定する。Ollamaは
 *                          {@code GPU_RUNTIME}が空のホストでも{@code lbs-ollama}が存在する(CPU実行)ため、
 *                          存在だけではGPU構成を判別できない
 * @param cpuProfile        CPU構成のコンテナを作るcomposeプロファイル(運用手順の案内に使う)
 */
public record ComputeTarget(
        String id,
        String displayName,
        String gpuContainerName,
        String cpuContainerName,
        String healthUrl,
        boolean gpuByRuntime,
        String cpuProfile) {

    /** ComfyUI型(コンテナの存在でGPU構成を判定し、URLの疎通で成功を判定する)。 */
    public ComputeTarget(String id, String gpuContainerName, String cpuContainerName, String healthUrl) {
        this(id, "ComfyUI", gpuContainerName, cpuContainerName, healthUrl, false, "cpu");
    }

    /** 成功判定にコンテナのヘルスチェックを使うか。 */
    boolean usesContainerHealth() {
        return healthUrl == null;
    }

    /** CPU構成のコンテナを作る運用手順(composeのサービス名はコンテナ名から{@code lbs-}を除いたもの)。 */
    String cpuCreateCommand() {
        return "docker compose --profile " + cpuProfile + " create " + cpuContainerName.replaceFirst("^lbs-", "");
    }
}
