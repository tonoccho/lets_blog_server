package com.letsblog.media.service;

import com.letsblog.media.domain.GeneratedImage;
import com.letsblog.media.dto.CreateGeneratedImageRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 利用者が手元からアップロードした画像を検証し、切り抜き・拡縮せず元の解像度のまま
 * 生成画像ギャラリーへ登録する(issue #1599、解像度の扱いは#1654で変更)。
 *
 * <p>対応形式はJPEG/PNGのみ。形式は申告されたContent-Typeではなく先頭のバイト列(マジックナンバー)で
 * 判定する。上限は20MB(multipart上限と同じ)。元の画像は保持せず、メタ情報を除いて再エンコードしたものだけを保存する(形式は維持)。
 * 拒否した場合は何も保存しない(検証と変換が終わってから初めて保存する)。
 * 登録後、再エンコード済みの画像でAIタグ付けを非同期に依頼する(issue #1600、{@link UploadedImageTagService})。
 *
 * <p>認可はこのクラスでは行わない。呼び出し元のコントローラが先に判定する。
 */
@Service
public class GeneratedImageUploadService {

    private static final Logger log = LoggerFactory.getLogger(GeneratedImageUploadService.class);

    /** アップロードできるファイルサイズの上限(20MB)。 */
    public static final int MAX_UPLOAD_BYTES = 20 * 1024 * 1024;

    /** 出所の値。generated_images.provider に入る。 */
    static final String PROVIDER_UPLOAD = "UPLOAD";

    private final ImageResizeService imageResizeService;
    private final GeneratedImageCreationService generatedImageCreationService;
    private final UploadedImageTagService uploadedImageTagService;

    public GeneratedImageUploadService(
            ImageResizeService imageResizeService, GeneratedImageCreationService generatedImageCreationService,
            UploadedImageTagService uploadedImageTagService) {
        this.imageResizeService = imageResizeService;
        this.generatedImageCreationService = generatedImageCreationService;
        this.uploadedImageTagService = uploadedImageTagService;
    }

    public GeneratedImage upload(Long projectId, byte[] data) {
        if (data == null || data.length == 0) {
            throw new InvalidImageUploadException("画像ファイルを選択してください。");
        }
        if (data.length > MAX_UPLOAD_BYTES) {
            throw new InvalidImageUploadException("ファイルサイズが上限(20MB)を超えています。");
        }
        String mimeType = sniffMimeType(data);
        if (mimeType == null) {
            throw new InvalidImageUploadException("対応していない画像形式です。JPEGまたはPNGを選択してください。");
        }

        ImageResizeService.ReencodedImage converted =
                imageResizeService.reencodeKeepingResolution(data, mimeType);

        GeneratedImage saved = generatedImageCreationService.create(new CreateGeneratedImageRequest(
                projectId, null, null, null, null, null, null, null,
                converted.width(), converted.height(), null, null, null, null, null,
                converted.mimeType(), PROVIDER_UPLOAD, null, converted.data()));
        requestAiTagging(saved, projectId, converted);
        return saved;
    }

    /**
     * 保存した<b>再エンコード後</b>の画像(EXIF/GPS除去済み。元ファイルではない)でAIタグ付けを依頼する
     * (issue #1600)。タグ付けは応答の後に非同期で行う補助機能なので、依頼の失敗(スレッドプール満杯など)で
     * アップロードを失敗させない。
     */
    private void requestAiTagging(GeneratedImage saved, Long projectId, ImageResizeService.ReencodedImage converted) {
        try {
            uploadedImageTagService.tagAsync(saved.getId(), projectId, converted.mimeType(), converted.data());
        } catch (RuntimeException e) {
            log.warn("アップロード画像のAIタグ付けを依頼できませんでした(タグなしで登録します): {}", e.getMessage());
        }
    }

    /** 先頭のバイト列からJPEG/PNGを判定する。どちらでもなければnull。 */
    private static String sniffMimeType(byte[] data) {
        if (data.length >= 3
                && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 && (data[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (data.length >= 8
                && (data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G'
                && data[4] == 0x0D && data[5] == 0x0A && data[6] == 0x1A && data[7] == 0x0A) {
            return "image/png";
        }
        return null;
    }
}
