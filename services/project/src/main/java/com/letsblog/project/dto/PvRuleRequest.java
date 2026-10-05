package com.letsblog.project.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** PV 達成ルールの追加(issue #1578)。{@code period}は {@code daily}(1日)か {@code total}(累計)。 */
public record PvRuleRequest(@NotBlank String period, @NotNull @Min(1) Integer threshold) {
}
