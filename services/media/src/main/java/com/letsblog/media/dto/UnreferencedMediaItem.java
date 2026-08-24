package com.letsblog.media.dto;

/** ガベージコレクション画面の一覧行(issue #500)。 */
public record UnreferencedMediaItem(String mediaId, String guid, String title, String mimeType, String uploadedAt) {
}
