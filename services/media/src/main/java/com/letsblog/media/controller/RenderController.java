package com.letsblog.media.controller;

import com.letsblog.media.ai.PenpotClient;
import com.letsblog.media.dto.PenpotDesignFileRequest;
import com.letsblog.media.dto.PenpotDesignFileResponse;
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
 * GPU/外部プロセス依存のレンダリング(PlantUML/Recharts/Penpot、issue #573)を担うエンドポイント。
 *
 * <p>PlantUMLはlegacy-apiが従来公開していた単体レンダリング用エンドポイント(動作確認・将来的な
 * プレビュー用途)をそのまま移設したもの。Recharts/Penpotは新規に追加したエンドポイントで、
 * legacy-api側に残る{@code PlantUmlEmbedService}/{@code PlantUmlTagRenderService}/
 * {@code RechartsTagRenderService}/{@code CustomTagGenerationService}が、CMSアップロード等の
 * オーケストレーション自体は引き続きlegacy-api側で行いつつ、実際の(遅い・外部プロセス依存の)
 * レンダリング処理だけをここへ委譲するために呼び出す(#573のPR説明、投稿パイプラインへの影響を
 * 最小化するための設計判断を参照)。
 */
@RestController
public class RenderController {

    private final PlantUmlClient plantUmlClient;
    private final RechartsRenderer rechartsRenderer;
    private final PenpotClient penpotClient;

    public RenderController(PlantUmlClient plantUmlClient, RechartsRenderer rechartsRenderer, PenpotClient penpotClient) {
        this.plantUmlClient = plantUmlClient;
        this.rechartsRenderer = rechartsRenderer;
        this.penpotClient = penpotClient;
    }

    @PostMapping("/api/render/plantuml")
    public ResponseEntity<byte[]> renderPlantUml(@Valid @RequestBody PlantUmlRenderRequest request) {
        byte[] png = plantUmlClient.renderPng(request.source());
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(png);
    }

    @PostMapping("/api/render/recharts")
    public RechartsRenderResponse renderRecharts(@RequestBody RechartsChartConfig config) {
        return new RechartsRenderResponse(rechartsRenderer.render(config));
    }

    @PostMapping("/api/render/penpot/design-file")
    public PenpotDesignFileResponse createPenpotDesignFile(@Valid @RequestBody PenpotDesignFileRequest request) {
        PenpotClient.DesignFile file = penpotClient.createDesignFile(request.fileName(), request.promptContext());
        return new PenpotDesignFileResponse(file.fileId(), file.projectId(), file.url());
    }
}
