package com.letsblog.content.dto;

public record ValidationWarning(
    String type,
    String message,
    Integer line
) {
    public static ValidationWarning of(String type, String message) {
        return new ValidationWarning(type, message, null);
    }

    public static ValidationWarning of(String type, String message, Integer line) {
        return new ValidationWarning(type, message, line);
    }
}
