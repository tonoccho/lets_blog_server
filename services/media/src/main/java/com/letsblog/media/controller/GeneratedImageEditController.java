package com.letsblog.media.controller;

import com.letsblog.media.domain.GeneratedImage;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.media.dto.EditGeneratedImageRequest;
import com.letsblog.media.dto.GeneratedImageDetailResponse;
import com.letsblog.media.dto.UtcDateTimes;
import com.letsblog.media.repository.GeneratedImageRepository;
import com.letsblog.media.service.AdminAuthorizationService;
import com.letsblog.media.service.GeneratedImageEditService;
import com.letsblog.media.service.GeneratedImageNotFoundException;
import com.letsblog.media.service.ImageEditOperation;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 生成画像を回転・反転・切り抜きして、新しい画像として保存する(issue #1655)。
 *
 * <p>画像の所属プロジェクトのメンバーまたはadminのみ(他のギャラリー操作と同じ)。存在しない画像は404、
 * 非メンバーは403で、いずれもファイルを読む前に判定する。要求はJSONの小さな本文だけで画像バイナリは
 * 運ばないため、gatewayでは{@code api-global}バケットに分類される(upload-endpointではない)。
 */
@RestController
public class GeneratedImageEditController {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final GeneratedImageRepository generatedImageRepository;
    private final AdminAuthorizationService adminAuthorizationService;
    private final GeneratedImageEditService generatedImageEditService;

    public GeneratedImageEditController(
            GeneratedImageRepository generatedImageRepository,
            AdminAuthorizationService adminAuthorizationService,
            GeneratedImageEditService generatedImageEditService) {
        this.generatedImageRepository = generatedImageRepository;
        this.adminAuthorizationService = adminAuthorizationService;
        this.generatedImageEditService = generatedImageEditService;
    }

    @PostMapping("/api/generated-images/{id}/edit")
    @ResponseStatus(HttpStatus.CREATED)
    public GeneratedImageDetailResponse edit(@PathVariable Long id, @RequestBody EditGeneratedImageRequest request) {
        GeneratedImage source = generatedImageRepository.findById(id)
                .orElseThrow(() -> new GeneratedImageNotFoundException("id: " + id));
        adminAuthorizationService.requireProjectMemberOrAdminForResource(source.getProjectId());
        List<ImageEditOperation> operations =
                request.operations() != null ? request.operations() : List.of();
        return toResponse(generatedImageEditService.edit(source, operations, request.crop(), request.adjustment()));
    }

    private static GeneratedImageDetailResponse toResponse(GeneratedImage image) {
        return new GeneratedImageDetailResponse(
                image.getId(), image.getProjectId(), image.getPrompt(), image.getNegativePrompt(),
                image.getSteps(), null, image.getSamplerName(), image.getScheduler(), image.getSeed(),
                image.getWidth(), image.getHeight(), image.getBatchSize(), image.getBatchIndex(),
                image.getCheckpoint(), image.getLoraName(), null,
                UtcDateTimes.toInstant(image.getCreatedAt()), parseTags(image.getTagsJson()), image.getProvider(),
                image.getFolderId(), image.getSourceImageId());
    }

    /** 引き継いだタグ({@code tagsJson})。読めなければ空として扱う(一覧の応答と同じ)。 */
    private static List<String> parseTags(String tagsJson) {
        if (tagsJson == null || tagsJson.isBlank()) {
            return List.of();
        }
        try {
            return OBJECT_MAPPER.readValue(tagsJson, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }
}
