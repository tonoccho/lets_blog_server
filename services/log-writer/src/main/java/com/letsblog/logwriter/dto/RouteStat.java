package com.letsblog.logwriter.dto;

/**
 * ルート別集計の1行(issue #1471)。{@code path}は数値ID・UUIDを{@code {id}}へ、クエリ文字列を除いた形。
 * p50/p95はnearest-rank法。
 */
public record RouteStat(String method, String path, long count, long p50Ms, long p95Ms, long maxMs) {
}
