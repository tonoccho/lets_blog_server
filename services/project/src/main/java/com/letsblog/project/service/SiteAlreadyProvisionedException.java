package com.letsblog.project.service;

/**
 * provisioning agent側に対象slugのWordPressサイト実体(ディレクトリ/DB)が既に存在し、
 * 今回のリクエストでは何も新規作成しなかった場合にスローされる。
 */
public class SiteAlreadyProvisionedException extends RuntimeException {
    public SiteAlreadyProvisionedException(String message, Throwable cause) {
        super(message, cause);
    }
}
