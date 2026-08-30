package com.letsblog.project.service;

public class SshKeyPairNotFoundException extends RuntimeException {
    public SshKeyPairNotFoundException(String message) {
        super(message);
    }
}
