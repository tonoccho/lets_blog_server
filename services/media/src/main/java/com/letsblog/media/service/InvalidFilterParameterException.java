package com.letsblog.media.service;

/** 一覧の絞り込み条件が矛盾している(issue #1493)。直せるのは送り手なので400で返す。 */
public class InvalidFilterParameterException extends RuntimeException {

    public InvalidFilterParameterException(String message) {
        super(message);
    }
}
