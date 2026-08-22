package com.letsblog.api.service;

public class GenerationJobNotFoundException extends RuntimeException {
    public GenerationJobNotFoundException(String message) {
        super(message);
    }
}
