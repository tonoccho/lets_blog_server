package com.letsblog.publishing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.Map;

/**
 * project-serviceからのletsblogプラグインへの同期依頼(issue #1558)。{@link CmsBridgeCredentialsRequest}と同じ
 * 認証情報に、送る内容(JSON文字列)とそのSHA-256(16進)を添える。ハッシュはプラグインが受け取った内容と
 * 照合し、食い違えば保存しない。
 */
public record CmsBridgeLetsblogSyncRequest(
        @NotNull String cmsType,
        @NotEmpty Map<String, String> credentials,
        @NotBlank String payload,
        @NotBlank @Pattern(regexp = "[0-9a-f]{64}", message = "hashはSHA-256の16進64文字で指定してください") String hash
) {
}
