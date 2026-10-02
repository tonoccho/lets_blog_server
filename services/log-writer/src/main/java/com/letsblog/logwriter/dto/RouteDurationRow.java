package com.letsblog.logwriter.dto;

/** ルート別集計の入力。operation_logsの1行から集計に必要な3列だけを取り出したもの(issue #1471)。 */
public record RouteDurationRow(String method, String path, Long durationMs) {
}
