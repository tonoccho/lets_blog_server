package com.letsblog.api.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.api.ai.GeneratedImageStorageService;
import com.letsblog.api.domain.GeneratedImage;
import com.letsblog.api.dto.GeneratedImageDetailResponse;
import com.letsblog.api.dto.GeneratedImageSummaryResponse;
import com.letsblog.api.dto.UpdateGeneratedImageTagsRequest;
import com.letsblog.api.repository.GeneratedImageRepository;
import com.letsblog.api.service.GeneratedImageNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * ComfyUIで生成した画像とパラメータの一覧・詳細・バイナリ取得(Web管理画面のギャラリー表示用)。
 */
@RestController
public class GeneratedImageController {

    private static final Logger log = LoggerFactory.getLogger(GeneratedImageController.class);

    private final GeneratedImageRepository generatedImageRepository;
    private final GeneratedImageStorageService generatedImageStorageService;
    private final ObjectMapper objectMapper;

    public GeneratedImageController(GeneratedImageRepository generatedImageRepository,
                                     GeneratedImageStorageService generatedImageStorageService,
                                     ObjectMapper objectMapper) {
        this.generatedImageRepository = generatedImageRepository;
        this.generatedImageStorageService = generatedImageStorageService;
        this.objectMapper = objectMapper;
    }

    /**
     * 一覧。tag指定時は、そのタグを持つ画像だけに絞り込む(大文字小文字を区別しない完全一致、issue #281)。
     */
    @GetMapping("/api/generated-images")
    public List<GeneratedImageSummaryResponse> list(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String tag) {
        List<GeneratedImage> images = projectId != null
                ? generatedImageRepository.findAllByProjectIdOrderByCreatedAtDesc(projectId)
                : generatedImageRepository.findAllByOrderByCreatedAtDesc();
        return images.stream()
                .map(image -> new GeneratedImageSummaryResponse(
                        image.getId(), image.getProjectId(), image.getPrompt(),
                        image.getCheckpoint(), image.getCreatedAt(), parseTags(image.getTagsJson()),
                        image.getProvider()))
                .filter(response -> tag == null || response.tags().stream().anyMatch(t -> t.equalsIgnoreCase(tag)))
                .toList();
    }

    @GetMapping("/api/generated-images/{id}")
    public GeneratedImageDetailResponse get(@PathVariable Long id) {
        return toDetailResponse(findOrThrow(id));
    }

    /** 自動生成されたタグを手動で編集・追加する(issue #281)。 */
    @PutMapping("/api/generated-images/{id}/tags")
    public GeneratedImageDetailResponse updateTags(
            @PathVariable Long id, @RequestBody UpdateGeneratedImageTagsRequest request) {
        GeneratedImage image = findOrThrow(id);
        image.setTagsJson(serializeTags(request.tags()));
        return toDetailResponse(generatedImageRepository.save(image));
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

    private GeneratedImageDetailResponse toDetailResponse(GeneratedImage image) {
        return new GeneratedImageDetailResponse(
                image.getId(), image.getProjectId(), image.getPrompt(), image.getNegativePrompt(),
                image.getSteps(), image.getCfgScale() != null ? image.getCfgScale().doubleValue() : null,
                image.getSamplerName(), image.getScheduler(), image.getSeed(),
                image.getWidth(), image.getHeight(), image.getBatchSize(), image.getCheckpoint(),
                image.getLoraName(), image.getLoraWeight() != null ? image.getLoraWeight().doubleValue() : null,
                image.getCreatedAt(), parseTags(image.getTagsJson()), image.getProvider());
    }

    private GeneratedImage findOrThrow(Long id) {
        return generatedImageRepository.findById(id)
                .orElseThrow(() -> new GeneratedImageNotFoundException("id: " + id));
    }

    private List<String> parseTags(String tagsJson) {
        if (tagsJson == null || tagsJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(tagsJson, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            log.warn("タグ情報のパースに失敗しました(空のタグ一覧として扱います): {}", e.getMessage());
            return List.of();
        }
    }

    private String serializeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(tags);
        } catch (Exception e) {
            log.warn("タグ情報のシリアライズに失敗しました: {}", e.getMessage());
            return null;
        }
    }
}
