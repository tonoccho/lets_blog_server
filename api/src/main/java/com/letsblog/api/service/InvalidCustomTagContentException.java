package com.letsblog.api.service;

public class InvalidCustomTagContentException extends RuntimeException {
    public InvalidCustomTagContentException(String message) {
        super(message);
    }
}
