package com.letsblog.api.service;

public class QrCodeGenerationException extends RuntimeException {
    public QrCodeGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
