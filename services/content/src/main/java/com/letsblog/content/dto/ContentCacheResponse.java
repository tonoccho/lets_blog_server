package com.letsblog.content.dto;

import com.letsblog.content.domain.ContentType;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * [blogcard]/[amazon] 組み込みタグ向けキャッシュAPIのレスポンス。
 * dataはtypeに応じてキーが異なる(BLOGCARD: title/description/imageUrl/siteName/url,
 * AMAZON: productName/imageUrl/price/productUrl/summary)。
 */
public record ContentCacheResponse(
        String url,
        ContentType type,
        Map<String, String> data,
        LocalDateTime lastCheckedAt,
        LocalDateTime lastUpdatedAt) {
}
