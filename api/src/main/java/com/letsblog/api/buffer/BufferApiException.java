package com.letsblog.api.buffer;

public class BufferApiException extends RuntimeException {
    public BufferApiException(String message) {
        super(message);
    }

    public BufferApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
