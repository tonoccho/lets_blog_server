package com.letsblog.project.service;

/** サイト名が空(または空白のみ)で更新しようとした場合。HTTP 400として返す。 */
public class InvalidSiteNameException extends RuntimeException {
    public InvalidSiteNameException(String message) {
        super(message);
    }
}
