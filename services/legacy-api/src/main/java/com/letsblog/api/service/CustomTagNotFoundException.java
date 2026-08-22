package com.letsblog.api.service;

public class CustomTagNotFoundException extends RuntimeException {
    public CustomTagNotFoundException(String message) {
        super(message);
    }
}
