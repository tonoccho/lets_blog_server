package com.letsblog.api.cms;

public class CmsApiException extends RuntimeException {
    public CmsApiException(String message) {
        super(message);
    }

    public CmsApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
