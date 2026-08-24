package com.letsblog.media.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.media.ai.GeneratedImageStorageService;
import com.letsblog.media.domain.GeneratedImage;
import com.letsblog.media.dto.CreateGeneratedImageRequest;
import com.letsblog.media.dto.GeneratedImageDetailResponse;
import com.letsblog.media.dto.GeneratedImageSummaryResponse;
import com.letsblog.media.dto.UpdateGeneratedImageTagsRequest;
import com.letsblog.media.repository.GeneratedImageRepository;
import com.letsblog.media.service.GeneratedImageNotFoundException;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
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

    /**
     * legacy-apiの{@code AiAssistService#generateImage}が、実際の画像生成(ComfyUI/ChatGPT呼び出し、
     * 引き続きlegacy-api側で行う)の後に呼ぶ(issue #573 stage4)。ファイル保存とDB行作成を
     * まとめて行う。
     */
    @PostMapping("/api/generated-images")
    @ResponseStatus(HttpStatus.CREATED)
    public GeneratedImageDetailResponse create(@Valid @RequestBody CreateGeneratedImageRequest request) {
        String filePath = generatedImageStorageService.store(request.projectId(), request.imageData());
        GeneratedImage image = new GeneratedImage();
        image.setProjectId(request.projectId());
        image.setPrompt(request.prompt());
        image.setNegativePrompt(request.negativePrompt());
        image.setSteps(request.steps());
        image.setCfgScale(request.cfgScale() != null ? BigDecimal.valueOf(request.cfgScale()) : null);
        image.setSamplerName(request.samplerName());
        image.setScheduler(request.scheduler());
        image.setSeed(request.seed());
        image.setWidth(request.width());
        image.setHeight(request.height());
        image.setBatchSize(request.batchSize());
        image.setCheckpoint(request.checkpoint());
        image.setLoraName(request.loraName());
        image.setLoraWeight(request.loraWeight() != null ? BigDecimal.valueOf(request.loraWeight()) : null);
        image.setFilePath(filePath);
        image.setMimeType(request.mimeType());
        image.setProvider(request.provider());
        image.setTagsJson(request.tagsJson());
        return toDetailResponse(generatedImageRepository.save(image));
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
