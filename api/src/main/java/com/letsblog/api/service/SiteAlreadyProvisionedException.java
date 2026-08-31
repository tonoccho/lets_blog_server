package com.letsblog.api.service;

/**
 * provisioning agent側に対象slugのWordPressサイト実体(ディレクトリ/DB)が既に存在し、
 * 今回のリクエストでは何も新規作成しなかった場合にスローされる。
 * {@link ProvisioningException}と区別することで、呼び出し元が誤って
 * (このリクエストが作成していない)既存サイトをdeprovisionしないようにする。
 */
public class SiteAlreadyProvisionedException extends RuntimeException {
    public SiteAlreadyProvisionedException(String message, Throwable cause) {
        super(message, cause);
    }
}
