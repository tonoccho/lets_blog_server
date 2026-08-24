package com.letsblog.media.controller;

import com.letsblog.media.ai.AiServiceException;
import com.letsblog.media.client.CmsBridgeClient;
import com.letsblog.media.client.MediaUploadResult;
import com.letsblog.media.service.ImageResizeService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * legacy-apiから移設(issue #573 stage3)。CMS(WordPress)のメディアライブラリへ直接画像を
 * アップロードするエンドポイント。メタ情報(EXIF等)の除去はここ(media-service側)で行い、
 * 除去済みのバイト列のみを{@link CmsBridgeClient}経由でlegacy-apiへ送る(実際のCMSアップロード
 * 自体はCMS接続情報を持つlegacy-apiが実行する。#573 stage3のCMSブリッジ設計、PR説明参照)。
 */
@RestController
public class MediaController {

    private final CmsBridgeClient cmsBridgeClient;
    private final ImageResizeService imageResizeService;
    private final HttpServletRequest request;

    public MediaController(CmsBridgeClient cmsBridgeClient, ImageResizeService imageResizeService,
            HttpServletRequest request) {
        this.cmsBridgeClient = cmsBridgeClient;
        this.imageResizeService = imageResizeService;
        this.request = request;
    }

    @PostMapping(value = "/api/media/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MediaUploadResult upload(@RequestParam("site") String site, @RequestPart("file") MultipartFile file) {
        try {
            byte[] bytes = imageResizeService.stripMetadata(file.getBytes(), file.getContentType());
            return cmsBridgeClient.uploadMedia(
                    site, file.getOriginalFilename(), file.getContentType(), bytes, bearerToken());
        } catch (IOException e) {
            throw new AiServiceException("画像の読み込みに失敗しました", e);
        }
    }

    private String bearerToken() {
        return request.getHeader(HttpHeaders.AUTHORIZATION);
    }
}
