package com.letsblog.api.service;

public class SshKeyPairNotFoundException extends RuntimeException {
    public SshKeyPairNotFoundException(String message) {
        super(message);
    }
}
