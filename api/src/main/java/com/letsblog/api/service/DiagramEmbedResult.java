package com.letsblog.api.service;

import java.util.Map;

/**
 * PlantUMLダイアグラム埋め込み処理(PlantUmlEmbedService/PlantUmlTagRenderService)の結果。
 * 画像参照差し替え後のMarkdownと、更新後のアップロード済みダイアグラムキャッシュを返す。
 */
record DiagramEmbedResult(String markdown, Map<String, UploadedImageInfo> uploadedImages) {
}
