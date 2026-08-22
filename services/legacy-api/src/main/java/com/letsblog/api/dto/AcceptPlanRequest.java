package com.letsblog.api.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record AcceptPlanRequest(
        @NotEmpty(message = "選択するタイトルが1件以上必要です")
        List<String> titles
) {
}
