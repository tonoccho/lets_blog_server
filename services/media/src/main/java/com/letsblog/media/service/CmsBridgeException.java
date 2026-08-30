package com.letsblog.media.service;

/**
 * publishing-serviceのCMSブリッジ(/api/internal/publishing/**、issue #573 stage3でlegacy-apiに
 * /api/internal/cms/**として新設、issue #709でpublishing-serviceへ移管、CmsBridgeClient参照)
 * 呼び出しの失敗を表す。
 */
public class CmsBridgeException extends RuntimeException {
    public CmsBridgeException(String message, Throwable cause) {
        super(message, cause);
    }
}
