package com.letsblog.ai.service;

public class GenerationJobNotFoundException extends RuntimeException {
    public GenerationJobNotFoundException(String message) {
        super(message);
    }
}
