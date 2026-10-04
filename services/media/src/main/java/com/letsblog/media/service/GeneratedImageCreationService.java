package com.letsblog.media.service;

import com.letsblog.media.ai.GeneratedImageStorageService;
import com.letsblog.media.domain.GeneratedImage;
import com.letsblog.media.dto.CreateGeneratedImageRequest;
import com.letsblog.media.messaging.DomainEventPublisher;
import com.letsblog.media.repository.GeneratedImageRepository;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;

/**
 * 生成画像のファイル保存とDB行作成({@code generated_images})をまとめて行う。
 *
 * <p>issue #583まで、この処理の入口は{@code POST /api/generated-images}だけだった
 * (画像生成そのものはlegacy-apiにあり、生成後にHTTPでここへ保存を依頼していた)。
 * #583で画像生成本体がmedia-serviceへ移ったため、{@link ImageGenerationService}が
 * <b>同じサービス内から直接</b>呼べるようにコントローラから切り出した。
 * 外部からの{@code POST /api/generated-images}(VSCode拡張等)も引き続き同じ経路を通る。
 *
 * <p>認可はこのクラスでは行わない。呼び出し元(コントローラ/{@code ImageGenerationService})が
 * 先に{@code requireProjectMemberOrAdminForResource}相当の判定を済ませる。
 */
@Service
public class GeneratedImageCreationService {

    private final GeneratedImageRepository generatedImageRepository;
    private final GeneratedImageStorageService generatedImageStorageService;
    private final DomainEventPublisher domainEventPublisher;

    public GeneratedImageCreationService(
            GeneratedImageRepository generatedImageRepository,
            GeneratedImageStorageService generatedImageStorageService,
            DomainEventPublisher domainEventPublisher) {
        this.generatedImageRepository = generatedImageRepository;
        this.generatedImageStorageService = generatedImageStorageService;
        this.domainEventPublisher = domainEventPublisher;
    }

    public GeneratedImage create(CreateGeneratedImageRequest request) {
        // issue #1599: アップロードされたJPEGは.jpgで保存する。それ以外は従来どおり.png。
        String filePath = "image/jpeg".equalsIgnoreCase(request.mimeType())
                ? generatedImageStorageService.store(request.projectId(), request.imageData(), "jpg")
                : generatedImageStorageService.store(request.projectId(), request.imageData());
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
        // issue #1101: バッチ内の位置(0起点)。単発保存や#1101以前の経路ではnull。
        image.setBatchIndex(request.batchIndex());
        image.setCheckpoint(request.checkpoint());
        image.setLoraName(request.loraName());
        image.setLoraWeight(request.loraWeight() != null ? BigDecimal.valueOf(request.loraWeight()) : null);
        image.setFilePath(filePath);
        image.setMimeType(request.mimeType());
        image.setProvider(request.provider());
        image.setTagsJson(request.tagsJson());
        // issue #1601: img2imgの参照元。参照画像を使っていなければnull。
        image.setSourceImageId(request.sourceImageId());
        GeneratedImage saved = generatedImageRepository.save(image);
        domainEventPublisher.publishImageGenerated(saved.getId(), saved.getProjectId());
        return saved;
    }
}
