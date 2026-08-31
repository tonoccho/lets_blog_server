package com.letsblog.content.controller;

import com.letsblog.content.contentcache.ContentCacheService;
import com.letsblog.content.dto.ContentCacheResponse;
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

    /**
     * 認可不要: blogcard/amazon組み込みタグ(#147・#148・#149)のためのURLメタデータ取得で、
     * 記事を書く利用者が普通に使う(issue #830)。admin限定にすると機能が壊れ、
     * プロジェクトメンバー限定にしてもメンバーなら同じことができるので緩和にならない。
     *
     * <p><b>ただし宛先アドレスの検証が無く、内部アドレスへのSSRFになる。</b>これは認可ではなく
     * 入力検証で対処すべき別種の問題なので、#902 として分けて起票した。
     */
    @GetMapping
    public ContentCacheResponse resolve(@RequestParam String url) {
        return contentCacheService.resolve(url);
    }
}
