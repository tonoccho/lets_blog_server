package com.letsblog.media.service;

/**
 * legacy-apiのCMSブリッジ(/api/internal/cms/**、issue #573 stage3、CmsBridgeClient参照)呼び出しの
 * 失敗を表す。
 */
public class CmsBridgeException extends RuntimeException {
    public CmsBridgeException(String message, Throwable cause) {
        super(message, cause);
    }
}
