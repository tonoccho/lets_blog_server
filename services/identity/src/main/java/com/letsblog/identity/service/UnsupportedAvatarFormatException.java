package com.letsblog.identity.service;

/**
 * issue #1241 AC4: アバターとして受理できない形式(image/jpeg・image/png・image/webp以外。
 * GIF・SVGを含む)、またはバイト列が画像として読み込めない場合に送出する。
 */
public class UnsupportedAvatarFormatException extends RuntimeException {
    public UnsupportedAvatarFormatException(String message) {
        super(message);
    }
}
