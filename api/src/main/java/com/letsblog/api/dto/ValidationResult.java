package com.letsblog.api.dto;

import java.util.List;

public record ValidationResult(
    boolean isValid,
    List<ValidationError> errors,
    List<ValidationWarning> warnings
) {
    public static ValidationResult valid() {
        return new ValidationResult(true, List.of(), List.of());
    }

    public static ValidationResult invalid(List<ValidationError> errors, List<ValidationWarning> warnings) {
        return new ValidationResult(errors.isEmpty(), errors, warnings);
    }
}
