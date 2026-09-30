package com.letsblog.ai.dto;

/**
 * プロジェクトから見たAIプロバイダー1件分の接続情報と利用可否(issue #1499)。
 *
 * <p>APIキー・OAuthトークンの値は、いかなる項目にも含めない。{@code targetUrl}は疎通確認に使うURLで、
 * APIキー方式(OpenAI/Claude)は{@code null}。{@code detail}は異常時の理由で、正常時は{@code null}。
 */
public record AiConnectionResponse(
        Provider provider,
        String displayName,
        String targetUrl,
        Source source,
        Status status,
        String detail,
        boolean configured) {

    public enum Provider {
        OLLAMA, COMFYUI, OPENAI, CLAUDE
    }

    /** 設定の出所。platform-serviceのAppSettingService.SettingSourceの区分に、プロジェクト単位の上書き(将来)を足したもの。 */
    public enum Source {
        PROJECT, DATABASE, ENVIRONMENT, NONE
    }

    public enum Status {
        NORMAL, WARNING, ERROR
    }
}
