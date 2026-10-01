package com.letsblog.project.service;

/** サイトの管理画面パスが規則(同一オリジン内の相対パス)に違反している場合。HTTP 400として返す。 */
public class InvalidSiteAdminPathException extends RuntimeException {
    public InvalidSiteAdminPathException(String message) {
        super(message);
    }
}
