package com.letsblog.api.exception;

public class RateLimitExceededException extends RuntimeException {
    private final String rateLimiterName;

    public RateLimitExceededException(String message, String rateLimiterName) {
        super(message);
        this.rateLimiterName = rateLimiterName;
    }

    public String getRateLimiterName() {
        return rateLimiterName;
    }
}
