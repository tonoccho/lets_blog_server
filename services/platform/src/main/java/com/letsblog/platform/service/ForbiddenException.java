package com.letsblog.platform.service;

/**
 * admin権限が必要な操作を、admin以外が呼び出した場合に送出する(AdminAuthorizationService参照)。
 * GlobalExceptionHandlerが403として扱う。
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
