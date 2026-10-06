package com.letsblog.media.service;

import com.letsblog.media.ai.GeneratedImageStorageService;
import com.letsblog.media.domain.GeneratedImage;
import com.letsblog.media.dto.CreateGeneratedImageRequest;
import com.letsblog.media.repository.GeneratedImageRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 生成画像を回転・反転・切り抜きし、<b>新しい画像として</b>登録する(issue #1655)。元の画像は変えない。
 *
 * <p>新しい画像は元の画像のタグ・フォルダ・種別({@code provider})・prompt を引き継ぐ。prompt は、AI生成の
 * 画像がギャラリーで「アップロード画像」と表示されないようにするために引き継ぐ(他の生成パラメータは、
 * 編集後の画素を再現するものではないので引き継がない)。{@code sourceImageId}は付けない: 詳細モーダルが
 * 「参照元の画像」(img2img、#1601)と表示してしまうため。
 *
 * <p>認可はこのクラスでは行わない。呼び出し元のコントローラが先に判定する。
 */
@Service
public class GeneratedImageEditService {

    private final GeneratedImageStorageService storage;
    private final ImageResizeService imageResizeService;
    private final GeneratedImageCreationService creationService;
    private final GeneratedImageRepository repository;

    public GeneratedImageEditService(
            GeneratedImageStorageService storage, ImageResizeService imageResizeService,
            GeneratedImageCreationService creationService, GeneratedImageRepository repository) {
        this.storage = storage;
        this.imageResizeService = imageResizeService;
        this.creationService = creationService;
        this.repository = repository;
    }

    public GeneratedImage edit(GeneratedImage source, List<ImageEditOperation> operations, ImageCropRegion crop) {
        ImageResizeService.ReencodedImage edited = imageResizeService.applyEdits(
                storage.load(source.getFilePath()), source.getMimeType(), operations, crop);
        GeneratedImage created = creationService.create(new CreateGeneratedImageRequest(
                source.getProjectId(), source.getPrompt(), null, null, null, null, null, null,
                edited.width(), edited.height(), null, null, null, null, null,
                edited.mimeType(), source.getProvider(), source.getTagsJson(), edited.data()));
        if (source.getFolderId() == null) {
            return created;
        }
        created.setFolderId(source.getFolderId());
        return repository.save(created);
    }
}
