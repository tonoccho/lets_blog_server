package com.letsblog.project.service;

/**
 * 実行中のジョブの{@code result_payload}へ書く進行段階(issue #1479)。形状はmedia-serviceの
 * {@code JobProgressPayload}(phase/percent/bytesDone/bytesTotal)と揃えてあり、キューUIは
 * {@code phase}を見て表示する。構築はprovision-agentへの1回のPOSTで内部の進み具合を返さないので、
 * 使うのは{@code phase}だけ(他はnull)。
 */
public record JobProgressPayload(String phase, Integer percent, Long bytesDone, Long bytesTotal) {

    public static JobProgressPayload phase(String phase) {
        return new JobProgressPayload(phase, null, null, null);
    }
}
