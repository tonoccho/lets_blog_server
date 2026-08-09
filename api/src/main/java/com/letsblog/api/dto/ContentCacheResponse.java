package com.letsblog.api.dto;

import com.letsblog.api.domain.ContentType;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * [blogcard]/[amazon] 組み込みタグ向けキャッシュAPIのレスポンス。
 * dataはtypeに応じてキーが異なる(BLOGCARD: title/description/imageUrl/siteName/url,
 * AMAZON: productName/imageUrl/price/productUrl)。
 */
public record ContentCacheResponse(
        String url,
        ContentType type,
        Map<String, String> data,
        LocalDateTime lastCheckedAt,
        LocalDateTime lastUpdatedAt) {
}
