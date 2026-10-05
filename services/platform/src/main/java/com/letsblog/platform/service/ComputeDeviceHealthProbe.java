package com.letsblog.platform.service;

/** 切り替え後のサービスが応答できる状態かの疎通確認(issue #1399)。 */
public interface ComputeDeviceHealthProbe {

    /** {@code url} が HTTP 200 を返したときだけtrue。 */
    boolean isHealthy(String url);
}
