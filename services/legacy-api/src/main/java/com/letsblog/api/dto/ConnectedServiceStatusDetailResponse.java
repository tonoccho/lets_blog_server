package com.letsblog.api.dto;

import com.letsblog.api.dto.ConnectedServiceStatusResponse.Status;

import java.time.Instant;

/**
 * 接続サービスの稼働状況の詳細診断情報(issue #199)。admin限定で返す。
 * httpStatus/targetUrlはHTTPリクエストを伴わないチェック(データベース・Brave Search)ではnullになる。
 */
public record ConnectedServiceStatusDetailResponse(
        String id,
        String name,
        Status status,
        long responseTimeMs,
        Integer httpStatus,
        String errorMessage,
        String targetUrl,
        Instant checkedAt) {
}
