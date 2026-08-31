package com.letsblog.api.service;

public class ProjectUserNotFoundException extends RuntimeException {
    public ProjectUserNotFoundException(String message) {
        super(message);
    }
}
