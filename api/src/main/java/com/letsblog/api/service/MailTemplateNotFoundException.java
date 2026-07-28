package com.letsblog.api.service;

public class MailTemplateNotFoundException extends RuntimeException {
    public MailTemplateNotFoundException(String message) {
        super(message);
    }
}
