package com.letsblog.media.service;

/**
 * 画像編集の操作(issue #1655)。{@link ImageResizeService#applyEdits}は並べた順に適用する。
 * 回転は90度単位、反転は回転後の画像に対して行う。
 */
public enum ImageEditOperation {
    ROTATE_CW,
    ROTATE_CCW,
    FLIP_HORIZONTAL,
    FLIP_VERTICAL
}
