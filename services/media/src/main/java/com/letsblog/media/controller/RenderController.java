package com.letsblog.media.controller;

import com.letsblog.media.dto.PlantUmlRenderRequest;
import com.letsblog.media.dto.RechartsRenderResponse;
import com.letsblog.media.render.PlantUmlClient;
import com.letsblog.media.render.RechartsChartConfig;
import com.letsblog.media.render.RechartsRenderer;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * GPU/外部プロセス依存のレンダリング(PlantUML/Recharts、issue #573)を担うエンドポイント。
 *
 * <p>PlantUMLはlegacy-apiが従来公開していた単体レンダリング用エンドポイント(動作確認・将来的な
 * プレビュー用途)をそのまま移設したもの。Rechartsは新規に追加したエンドポイントで、
 * legacy-api側に残る{@code PlantUmlEmbedService}/{@code PlantUmlTagRenderService}/
 * {@code RechartsTagRenderService}が、CMSアップロード等の
 * オーケストレーション自体は引き続きlegacy-api側で行いつつ、実際の(遅い・外部プロセス依存の)
 * レンダリング処理だけをここへ委譲するために呼び出す(#573のPR説明、投稿パイプラインへの影響を
 * 最小化するための設計判断を参照)。
 */
@RestController
public class RenderController {

    private final PlantUmlClient plantUmlClient;
    private final RechartsRenderer rechartsRenderer;

    public RenderController(PlantUmlClient plantUmlClient, RechartsRenderer rechartsRenderer) {
        this.plantUmlClient = plantUmlClient;
        this.rechartsRenderer = rechartsRenderer;
    }

    /**
     * 認可不要: issue #830 でgatewayのルート表から/api/render/**を外したため外部から到達できない。
     * content-service/publishing-serviceのMediaRenderClientがapp.media-service-uriへ
     * コンテナ間で直接呼ぶ経路しか無く、認可はレンダリングを依頼する側が済ませている。
     * 処理自体も入力を受け取って画像を返すだけで、保存済みリソースには触れない。
     */
    @PostMapping("/api/render/plantuml")
    public ResponseEntity<byte[]> renderPlantUml(@Valid @RequestBody PlantUmlRenderRequest request) {
        byte[] png = plantUmlClient.renderPng(request.source());
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(png);
    }

    /** 認可不要: {@link #renderPlantUml}と同じ理由(gateway非経由・呼び出し元が認可済み、issue #830)。 */
    @PostMapping("/api/render/recharts")
    public RechartsRenderResponse renderRecharts(@RequestBody RechartsChartConfig config) {
        return new RechartsRenderResponse(rechartsRenderer.render(config));
    }
}
