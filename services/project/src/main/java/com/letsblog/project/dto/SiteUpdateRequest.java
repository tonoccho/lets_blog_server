package com.letsblog.project.dto;

import java.util.Map;

public record SiteUpdateRequest(
        String name,
        Map<String, String> credentials,
        /** null=変更しない、空文字=上書きを解除(NULL保存)、それ以外=その値で上書き。 */
        String adminPath
) {
}
