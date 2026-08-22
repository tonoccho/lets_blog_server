package com.letsblog.common.web;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * REST APIのエラーレスポンスの共通JSON形状。 {@code details} が null の場合は
 * JSON に出力しない({@code {"error": "..."}} のみになる)。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(String error, Object details) {

    public static ErrorResponse of(String error) {
        return new ErrorResponse(error, null);
    }

    public static ErrorResponse of(String error, Object details) {
        return new ErrorResponse(error, details);
    }
}
