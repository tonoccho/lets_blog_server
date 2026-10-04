package com.letsblog.media.service;

import com.letsblog.media.ai.GeneratedImageStorageService;
import com.letsblog.media.ai.ReferenceImage;
import com.letsblog.media.domain.GeneratedImage;
import com.letsblog.media.repository.GeneratedImageRepository;
import org.springframework.stereotype.Service;

/**
 * img2img(issue #1601)の参照画像の検証と読み込み。
 *
 * <p>参照できるのは<b>同じプロジェクトに今も存在する生成画像</b>だけ。要求者がそのプロジェクトの
 * メンバーであることは、呼び出し元のコントローラが{@code projectId}に対して確かめる
 * (参照画像は必ず同じプロジェクトのものなので、メンバーでなければ参照画像にも触れない)。
 */
@Service
public class ReferenceImageService {

    private final GeneratedImageRepository generatedImageRepository;
    private final GeneratedImageStorageService generatedImageStorageService;

    public ReferenceImageService(
            GeneratedImageRepository generatedImageRepository,
            GeneratedImageStorageService generatedImageStorageService) {
        this.generatedImageRepository = generatedImageRepository;
        this.generatedImageStorageService = generatedImageStorageService;
    }

    /** 参照画像として使えることを確かめる。使えなければ{@link InvalidReferenceImageException}。 */
    public GeneratedImage requireUsable(Long projectId, Long referenceImageId) {
        if (projectId == null) {
            throw new InvalidReferenceImageException("参照画像を使うにはprojectIdの指定が必要です");
        }
        return generatedImageRepository.findById(referenceImageId)
                .filter(image -> projectId.equals(image.getProjectId()))
                .orElseThrow(() -> new InvalidReferenceImageException(
                        "参照画像(id: " + referenceImageId + ")はこのプロジェクトに存在しません"));
    }

    /** 検証したうえで、保存済みのバイト列を読み込む。 */
    public ReferenceImage load(Long projectId, Long referenceImageId) {
        GeneratedImage image = requireUsable(projectId, referenceImageId);
        return new ReferenceImage(generatedImageStorageService.load(image.getFilePath()), image.getMimeType());
    }
}
