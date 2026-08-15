package com.letsblog.api.buffer;

/** Bufferの/updates/create.jsonが返す、作成された予約投稿(プロファイル1件分)。 */
public record BufferUpdate(String id, String profileId) {
}
