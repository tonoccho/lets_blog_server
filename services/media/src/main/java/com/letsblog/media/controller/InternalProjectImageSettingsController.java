package com.letsblog.media.controller;

import com.letsblog.media.dto.ArticleImageLongEdgePxBridgeResponse;
import com.letsblog.media.service.ProjectImageDefaultsResolver;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * publishing-service向けの内部ブリッジ(issue #583)。投稿画像のリサイズ目標px
 * ({@code project_image_settings.default_article_image_long_edge_px})の所有権が
 * media-serviceへ移ったため、{@code PostPublishService}はここへ問い合わせる。
 *
 * <p>#583以前はlegacy-apiの{@code ProjectUserBridgeController}が同じ値を返していた。
 *
 * <p>認可は、呼び出し元(publishing-service)が既に認可を済ませたリクエストのBearerトークンを
 * そのまま転送してもらう想定で、ここでは追加のチェックを行わない。
 * gatewayのルート表には載せないため外部から到達することはない。
 */
@RestController
@RequestMapping("/api/internal/media")
public class InternalProjectImageSettingsController {

    private final ProjectImageDefaultsResolver defaultsResolver;

    public InternalProjectImageSettingsController(ProjectImageDefaultsResolver defaultsResolver) {
        this.defaultsResolver = defaultsResolver;
    }

    /**
     * 認可不要: gatewayのルート表に載っておらず外部から到達できない内部ブリッジで、呼び出し元の
     * サービスが既に認可を済ませている(issue #583)。
     */
    @GetMapping("/projects/{projectId}/article-image-long-edge-px")
    public ArticleImageLongEdgePxBridgeResponse articleImageLongEdgePx(@PathVariable Long projectId) {
        return new ArticleImageLongEdgePxBridgeResponse(defaultsResolver.resolveArticleImageLongEdgePx(projectId));
    }
}
