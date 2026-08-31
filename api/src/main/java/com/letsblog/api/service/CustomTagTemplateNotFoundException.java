package com.letsblog.api.service;

public class CustomTagTemplateNotFoundException extends RuntimeException {
    public CustomTagTemplateNotFoundException(String message) {
        super(message);
    }

    public CustomTagTemplateNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
