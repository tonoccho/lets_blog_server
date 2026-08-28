package com.letsblog.publishing.service;

/**
 * アップロード済み画像/ダイアグラム1件分の情報。sha256は再投稿時に内容が変わっていないかの判定に使う
 * (PostPublishService, PlantUmlEmbedService, PlantUmlTagRenderServiceで共有する)。
 */
record UploadedImageInfo(String sha256, String url, String mediaId) {
}
