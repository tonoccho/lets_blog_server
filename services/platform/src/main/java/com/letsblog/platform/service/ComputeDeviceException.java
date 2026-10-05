package com.letsblog.platform.service;

import org.springframework.http.HttpStatus;

/** 演算デバイス切り替えの要求を受け付けられない(対象不明・選べない構成・適用中・Docker到達不能)。 */
public class ComputeDeviceException extends RuntimeException {

    private final HttpStatus status;

    public ComputeDeviceException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
