package com.letsblog.api.dto;

import jakarta.validation.constraints.Pattern;

public record UpdateProjectCssSelectorPrefixRequest(
        @Pattern(
                regexp = "^[a-zA-Z][a-zA-Z0-9_-]*$|^$",
                message = "英数字・ハイフン・アンダースコアのみ使用できます(空でプロジェクトのslugに戻せます)"
        )
        String cssSelectorPrefix
) {
}
