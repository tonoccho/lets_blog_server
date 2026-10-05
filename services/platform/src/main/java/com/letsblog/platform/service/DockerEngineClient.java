package com.letsblog.platform.service;

import java.util.List;

/**
 * Docker Engine APIのうち、演算デバイスの切り替えが使う3操作だけ(issue #1399)。
 *
 * <p>docker-socket-proxyが通すのは {@code GET /containers/json}、{@code POST /containers/{id}/start}、
 * {@code POST /containers/{id}/stop} だけである(Epic #551 / #701の方針の範囲内。作成・削除・exec・
 * イメージ操作は開けない)。この境界を超える操作をここへ足してはならない。
 */
public interface DockerEngineClient {

    /** このプロジェクトのコンテナ一覧(停止中を含む)。 */
    List<ContainerRef> listContainers();

    void startContainer(String id);

    void stopContainer(String id);

    /** @param name 先頭の{@code /}を除いたコンテナ名(例: {@code lbs-comfyui}) */
    record ContainerRef(String id, String name, String state) {

        public boolean running() {
            return "running".equals(state);
        }
    }
}
