package com.letsblog.publishing.service;

/**
 * アセット画像のMIMEと拡張子を、実際のバイト列(先頭のマジックナンバー)から決める(issue #1599)。
 *
 * <p>以前は「常にimage/pngとして保存されている」前提で固定していたが、アップロードされた画像は
 * JPEGで保存されることがある。JPEGのバイト列をPNGと名乗ってWordPressへ送ると、拡張子とMIMEが
 * 内容と食い違ったメディアになる。判別できなければ従来どおりPNGとして扱う。
 */
public enum AssetImageFormat {
    JPEG("image/jpeg", "jpg"),
    PNG("image/png", "png");

    private final String mimeType;
    private final String extension;

    AssetImageFormat(String mimeType, String extension) {
        this.mimeType = mimeType;
        this.extension = extension;
    }

    public String mimeType() {
        return mimeType;
    }

    public String extension() {
        return extension;
    }

    public static AssetImageFormat detect(byte[] data) {
        if (data != null && data.length >= 3
                && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 && (data[2] & 0xFF) == 0xFF) {
            return JPEG;
        }
        return PNG;
    }
}
