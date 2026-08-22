package com.letsblog.api.controller;

import com.letsblog.api.contentcache.ContentCacheService;
import com.letsblog.api.dto.ContentCacheResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * [blogcard]/[amazon] 組み込みタグのレンダリング処理から呼び出される内部API。
 * URLを渡すとOGP情報/Amazon商品情報をスクレイピング(またはキャッシュから取得)して返す。
 */
@RestController
@RequestMapping("/api/content-cache")
public class ContentCacheController {

    private final ContentCacheService contentCacheService;

    public ContentCacheController(ContentCacheService contentCacheService) {
        this.contentCacheService = contentCacheService;
    }

    @GetMapping
    public ContentCacheResponse resolve(@RequestParam String url) {
        return contentCacheService.resolve(url);
    }
}
