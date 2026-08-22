package com.letsblog.api.controller;

import com.letsblog.api.dto.PlantUmlRenderRequest;
import com.letsblog.api.render.PlantUmlClient;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * PlantUML図の単体レンダリング用エンドポイント(動作確認・将来的なプレビュー用途)。
 * 投稿パイプライン内での自動レンダリングは PlantUmlEmbedService が行う。
 */
@RestController
public class RenderController {

    private final PlantUmlClient plantUmlClient;

    public RenderController(PlantUmlClient plantUmlClient) {
        this.plantUmlClient = plantUmlClient;
    }

    @PostMapping("/api/render/plantuml")
    public ResponseEntity<byte[]> renderPlantUml(@Valid @RequestBody PlantUmlRenderRequest request) {
        byte[] png = plantUmlClient.renderPng(request.source());
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(png);
    }
}
