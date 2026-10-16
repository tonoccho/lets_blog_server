package com.letsblog.common.auth;

/**
 * extensionsのツール(Ollama・ComfyUI・PlantUML・ポータル)へ付けるトークン(#1804)を、
 * シークレットが設定されているのに取得できなかったことを表す。
 *
 * <p>呼び出し元はこれを受けて、ヘッダー無しで送り続けず、理由の分かるエラーにする(ログ、連携サービスの状況、
 * 接続テストの結果、各機能のエラー)。メッセージにはシークレットを含めない。
 */
public class ToolsTokenUnavailableException extends RuntimeException {

    public ToolsTokenUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
