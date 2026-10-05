package com.letsblog.publishing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.Map;

/**
 * project-serviceからの letsblog プラグインの `wp letsblog sns` 実行依頼(issue #1574)。
 * {@link CmsBridgeCredentialsRequest}と同じ認証情報に、サブコマンド名・SNS名・標準入力を添える。
 * 標準入力(`config set` のJSON)にはトークンが入るため、ログにも例外にも出さず、このリクエストの間だけ持つ。
 */
public record CmsBridgeLetsblogSnsRequest(
        @NotNull String cmsType,
        @NotEmpty Map<String, String> credentials,
        @NotBlank String command,
        @Pattern(regexp = "[a-z0-9_-]{1,32}", message = "snsは英小文字・数字・_・-の32文字以内で指定してください") String sns,
        String stdin
) {
}
