package com.letsblog.media.service;

import com.letsblog.media.domain.GeneratedImage;
import com.letsblog.media.dto.CreateGeneratedImageRequest;
import org.springframework.stereotype.Service;

/**
 * 利用者が手元からアップロードした画像を検証し、1920x1080(中央切り抜き)へ変換して
 * 生成画像ギャラリーへ登録する(issue #1599)。
 *
 * <p>対応形式はJPEG/PNGのみ。形式は申告されたContent-Typeではなく先頭のバイト列(マジックナンバー)で
 * 判定する。上限は20MB(multipart上限と同じ)。元の画像は保持せず、変換後のものだけを保存する。
 * 拒否した場合は何も保存しない(検証と変換が終わってから初めて保存する)。
 *
 * <p>認可はこのクラスでは行わない。呼び出し元のコントローラが先に判定する。
 */
@Service
public class GeneratedImageUploadService {

    /** アップロードできるファイルサイズの上限(20MB)。 */
    public static final int MAX_UPLOAD_BYTES = 20 * 1024 * 1024;

    /** 保存画像の解像度。 */
    static final int TARGET_WIDTH = 1920;
    static final int TARGET_HEIGHT = 1080;

    /** 出所の値。generated_images.provider に入る。 */
    static final String PROVIDER_UPLOAD = "UPLOAD";

    private final ImageResizeService imageResizeService;
    private final GeneratedImageCreationService generatedImageCreationService;

    public GeneratedImageUploadService(
            ImageResizeService imageResizeService, GeneratedImageCreationService generatedImageCreationService) {
        this.imageResizeService = imageResizeService;
        this.generatedImageCreationService = generatedImageCreationService;
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

        ImageResizeService.ResizeResult converted =
                imageResizeService.coverTo(data, mimeType, TARGET_WIDTH, TARGET_HEIGHT);

        return generatedImageCreationService.create(new CreateGeneratedImageRequest(
                projectId, null, null, null, null, null, null, null,
                TARGET_WIDTH, TARGET_HEIGHT, null, null, null, null, null,
                converted.mimeType(), PROVIDER_UPLOAD, null, converted.data()));
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
