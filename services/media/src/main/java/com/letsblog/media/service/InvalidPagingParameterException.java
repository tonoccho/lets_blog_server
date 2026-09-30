package com.letsblog.media.service;

/** limit/offsetが範囲外(issue #1472)。直せるのは送り手なので400で返す。 */
public class InvalidPagingParameterException extends RuntimeException {

    public InvalidPagingParameterException(String message) {
        super(message);
    }
}
