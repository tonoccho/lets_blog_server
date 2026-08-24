package com.letsblog.content.contentcache;

/** 対象URLの取得・スクレイピングに失敗した場合にスローする。 */
public class ContentScrapingException extends RuntimeException {
    public ContentScrapingException(String message, Throwable cause) {
        super(message, cause);
    }
}
