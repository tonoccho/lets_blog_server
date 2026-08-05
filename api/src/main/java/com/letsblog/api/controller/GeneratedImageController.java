package com.letsblog.api.controller;

import com.letsblog.api.ai.GeneratedImageStorageService;
import com.letsblog.api.domain.GeneratedImage;
import com.letsblog.api.dto.GeneratedImageDetailResponse;
import com.letsblog.api.dto.GeneratedImageSummaryResponse;
import com.letsblog.api.repository.GeneratedImageRepository;
import com.letsblog.api.service.GeneratedImageNotFoundException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * ComfyUIで生成した画像とパラメータの一覧・詳細・バイナリ取得(Web管理画面のギャラリー表示用)。
 */
@RestController
public class GeneratedImageController {

    private final GeneratedImageRepository generatedImageRepository;
    private final GeneratedImageStorageService generatedImageStorageService;

    public GeneratedImageController(GeneratedImageRepository generatedImageRepository,
                                     GeneratedImageStorageService generatedImageStorageService) {
        this.generatedImageRepository = generatedImageRepository;
        this.generatedImageStorageService = generatedImageStorageService;
    }

    @GetMapping("/api/generated-images")
    public List<GeneratedImageSummaryResponse> list(@RequestParam(required = false) Long projectId) {
        List<GeneratedImage> images = projectId != null
                ? generatedImageRepository.findAllByProjectIdOrderByCreatedAtDesc(projectId)
                : generatedImageRepository.findAllByOrderByCreatedAtDesc();
        return images.stream()
                .map(image -> new GeneratedImageSummaryResponse(
                        image.getId(), image.getProjectId(), image.getPrompt(),
                        image.getCheckpoint(), image.getCreatedAt()))
                .toList();
    }

    @GetMapping("/api/generated-images/{id}")
    public GeneratedImageDetailResponse get(@PathVariable Long id) {
        GeneratedImage image = findOrThrow(id);
        return new GeneratedImageDetailResponse(
                image.getId(), image.getProjectId(), image.getPrompt(), image.getNegativePrompt(),
                image.getSteps(), image.getCfgScale() != null ? image.getCfgScale().doubleValue() : null,
                image.getSamplerName(), image.getScheduler(), image.getSeed(),
                image.getWidth(), image.getHeight(), image.getBatchSize(), image.getCheckpoint(),
                image.getLoraName(), image.getLoraWeight() != null ? image.getLoraWeight().doubleValue() : null,
                image.getCreatedAt());
    }

    @GetMapping("/api/generated-images/{id}/file")
    public ResponseEntity<byte[]> getImageFile(@PathVariable Long id) {
        GeneratedImage image = findOrThrow(id);
        byte[] data = generatedImageStorageService.load(image.getFilePath());
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=" + id + ".png")
                .body(data);
    }

    @DeleteMapping("/api/generated-images/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        GeneratedImage image = findOrThrow(id);
        generatedImageStorageService.delete(image.getFilePath());
        generatedImageRepository.delete(image);
        return ResponseEntity.noContent().build();
    }

    private GeneratedImage findOrThrow(Long id) {
        return generatedImageRepository.findById(id)
                .orElseThrow(() -> new GeneratedImageNotFoundException("id: " + id));
    }
}
