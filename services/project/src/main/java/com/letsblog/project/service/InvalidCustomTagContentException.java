package com.letsblog.project.service;

public class InvalidCustomTagContentException extends RuntimeException {
    public InvalidCustomTagContentException(String message) {
        super(message);
    }
}
