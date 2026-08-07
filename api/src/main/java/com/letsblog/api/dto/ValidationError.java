package com.letsblog.api.dto;

public record ValidationError(
    String type,
    String message,
    Integer line,
    String severity
) {
    public static ValidationError of(String type, String message, String severity) {
        return new ValidationError(type, message, null, severity);
    }

    public static ValidationError of(String type, String message, Integer line, String severity) {
        return new ValidationError(type, message, line, severity);
    }
}
