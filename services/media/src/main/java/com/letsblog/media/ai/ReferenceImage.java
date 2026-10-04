package com.letsblog.media.ai;

/**
 * img2img(issue #1601)の入力になる参照画像。ギャラリーに保存済みの画像のバイト列とMIME。
 * ComfyUiClientが{@code /upload/image}でComfyUIへ送る。
 */
public record ReferenceImage(byte[] data, String mimeType) {
}
