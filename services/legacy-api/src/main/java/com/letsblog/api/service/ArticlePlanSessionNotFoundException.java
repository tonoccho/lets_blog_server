package com.letsblog.api.service;

public class ArticlePlanSessionNotFoundException extends RuntimeException {
    public ArticlePlanSessionNotFoundException(String message) {
        super(message);
    }
}
