package com.letsblog.api.service;

public class InvalidTotpCodeException extends RuntimeException {
    public InvalidTotpCodeException(String message) {
        super(message);
    }
}
