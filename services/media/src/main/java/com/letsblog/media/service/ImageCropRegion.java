package com.letsblog.media.service;

/**
 * 切り抜き範囲(画素単位、issue #1655)。座標は回転・反転を<b>適用した後</b>の画像の左上が原点。
 */
public record ImageCropRegion(int x, int y, int width, int height) {
}
