package com.letsblog.platform.service;

import java.util.List;

/**
 * Docker Engine APIのうち、演算デバイスの切り替えが使う4操作だけ(issue #1399 / #1585)。
 *
 * <p>docker-socket-proxyが通すのは {@code GET /containers/json}、{@code GET /containers/{id}/json}、
 * {@code POST /containers/{id}/start}、{@code POST /containers/{id}/stop} だけである(Epic #551 / #701の方針の範囲内。作成・削除・exec・
 * イメージ操作は開けない)。この境界を超える操作をここへ足してはならない。
 */
public interface DockerEngineClient {

    /** このプロジェクトのコンテナ一覧(停止中を含む)。 */
    List<ContainerRef> listContainers();

    /** コンテナの詳細(issue #1585)。GPU構成の判定({@code HostConfig.Runtime})とヘルスチェックの状態に使う。 */
    ContainerInspection inspectContainer(String id);

    void startContainer(String id);

    void stopContainer(String id);

    /** @param name 先頭の{@code /}を除いたコンテナ名(例: {@code lbs-comfyui}) */
    record ContainerRef(String id, String name, String state) {

        public boolean running() {
            return "running".equals(state);
        }
    }

    /**
     * @param runtime      {@code HostConfig.Runtime}。Dockerの既定ランタイムなら空
     * @param healthStatus {@code State.Health.Status}({@code healthy} / {@code starting} / {@code unhealthy})。
     *                     ヘルスチェックが無ければ空
     */
    record ContainerInspection(String runtime, String healthStatus) {

        public boolean nvidia() {
            return "nvidia".equals(runtime);
        }

        public boolean healthy() {
            return "healthy".equals(healthStatus);
        }
    }
}
