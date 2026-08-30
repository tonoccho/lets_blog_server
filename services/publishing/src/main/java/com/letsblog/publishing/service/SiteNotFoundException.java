package com.letsblog.publishing.service;

/** project-serviceの内部ブリッジがサイト未登録(404)を返した場合に投げる(issue #707)。 */
public class SiteNotFoundException extends RuntimeException {
    public SiteNotFoundException(String message) {
        super(message);
    }
}
