package com.letsblog.api.service;

public class TwoFactorSecretNotFoundException extends RuntimeException {
    public TwoFactorSecretNotFoundException(String message) {
        super(message);
    }
}
