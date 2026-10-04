package com.letsblog.media.controller;

import com.letsblog.media.domain.GeneratedImage;
import com.letsblog.media.dto.GeneratedImageDetailResponse;
import com.letsblog.media.dto.UtcDateTimes;
import com.letsblog.media.service.AdminAuthorizationService;
import com.letsblog.media.service.GeneratedImageUploadService;
import com.letsblog.media.service.InvalidImageUploadException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * 手元の画像をアップロードして生成画像ギャラリーへ登録する(issue #1599)。
 *
 * <p>プロジェクトのメンバーまたはadminのみ。ファイル本体を読む前に認可を判定する。
 * gatewayでは{@code upload-endpoint}バケットに分類される(実バイナリのアップロード、最大20MB)。
 */
@RestController
public class GeneratedImageUploadController {

    private final AdminAuthorizationService adminAuthorizationService;
    private final GeneratedImageUploadService generatedImageUploadService;

    public GeneratedImageUploadController(
            AdminAuthorizationService adminAuthorizationService,
            GeneratedImageUploadService generatedImageUploadService) {
        this.adminAuthorizationService = adminAuthorizationService;
        this.generatedImageUploadService = generatedImageUploadService;
    }

    @PostMapping(value = "/api/generated-images/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public GeneratedImageDetailResponse upload(
            @RequestParam Long projectId, @RequestParam("file") MultipartFile file) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        if (file.isEmpty()) {
            throw new InvalidImageUploadException("画像ファイルを選択してください。");
        }
        byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("アップロードされたファイルを読み込めませんでした", e);
        }
        return toResponse(generatedImageUploadService.upload(projectId, data));
    }

    private static GeneratedImageDetailResponse toResponse(GeneratedImage image) {
        return new GeneratedImageDetailResponse(
                image.getId(), image.getProjectId(), image.getPrompt(), image.getNegativePrompt(),
                image.getSteps(), null, image.getSamplerName(), image.getScheduler(), image.getSeed(),
                image.getWidth(), image.getHeight(), image.getBatchSize(), image.getBatchIndex(),
                image.getCheckpoint(), image.getLoraName(), null,
                UtcDateTimes.toInstant(image.getCreatedAt()), List.of(), image.getProvider(),
                image.getFolderId(), image.getSourceImageId());
    }
}
