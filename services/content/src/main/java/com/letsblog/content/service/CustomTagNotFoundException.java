package com.letsblog.content.service;

public class CustomTagNotFoundException extends RuntimeException {
    public CustomTagNotFoundException(String message) {
        super(message);
    }
}
