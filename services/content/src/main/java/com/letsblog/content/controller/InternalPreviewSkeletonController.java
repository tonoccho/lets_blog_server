package com.letsblog.content.controller;

import com.letsblog.content.dto.FetchAndSpliceRequest;
import com.letsblog.content.dto.FetchRealPostRequest;
import com.letsblog.content.dto.ThemeSkeletonResponse;
import com.letsblog.content.service.PreviewSkeletonFetcher;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * legacy-api側のArticlePreviewService#renderSkeleton/#renderRealPrivatePost(issue #575まで引き続き
 * legacy-apiに残る、Site/CMS認証情報に依存するテーマ骨格取得のオーケストレーション)向けの内部ブリッジ
 * (issue #576)。Playwrightを持つのはcontent-serviceになったため、実際のヘッドレスブラウザ操作
 * ({@link PreviewSkeletonFetcher}）はここへ委譲してもらう。CMS認証情報自体は受け取らず、
 * 既に解決済みのnavigateUrl・認証Cookie・差し替え内容(タイトル/本文/アイキャッチ)のみを受け取る。
 */
@RestController
public class InternalPreviewSkeletonController {

    private final PreviewSkeletonFetcher previewSkeletonFetcher;

    public InternalPreviewSkeletonController(PreviewSkeletonFetcher previewSkeletonFetcher) {
        this.previewSkeletonFetcher = previewSkeletonFetcher;
    }

    @PostMapping("/api/internal/content/preview-skeleton/fetch-and-splice")
    public ThemeSkeletonResponse fetchAndSplice(@RequestBody FetchAndSpliceRequest request) {
        return previewSkeletonFetcher.fetchAndSplice(
                request.url(), request.titleRendered(), request.contentRendered(), request.ourTitle(),
                request.ourContentHtml(), request.featuredImageDataUri());
    }

    @PostMapping("/api/internal/content/preview-skeleton/fetch-real-post")
    public ThemeSkeletonResponse fetchRealPost(@RequestBody FetchRealPostRequest request) {
        return previewSkeletonFetcher.fetchRealPost(request.url(), request.cookieName(), request.cookieValue());
    }
}
