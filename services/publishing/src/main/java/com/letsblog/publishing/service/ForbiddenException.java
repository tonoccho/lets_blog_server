package com.letsblog.publishing.service;

/** legacy-apiの{@code com.letsblog.api.service.ForbiddenException}をpublishing-serviceへ移設したもの(issue #708)。 */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}
