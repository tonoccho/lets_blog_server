package com.letsblog.media.service;

/** 指定されたフォルダが存在しない(issue #1493)。404で返す。 */
public class GeneratedImageFolderNotFoundException extends RuntimeException {
    public GeneratedImageFolderNotFoundException(String message) {
        super(message);
    }
}
