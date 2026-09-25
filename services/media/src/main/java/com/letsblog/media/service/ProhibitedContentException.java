package com.letsblog.media.service;

/** 不適切コンテンツフィルタによって画像生成プロンプトがブロックされた場合の例外(issue #532)。 */
public class ProhibitedContentException extends RuntimeException {
    public ProhibitedContentException(String message) {
        super(message);
    }
}
