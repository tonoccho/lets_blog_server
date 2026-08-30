package com.letsblog.project.client;

/**
 * legacy-apiの内部CMSブリッジ(/api/internal/project/cms/**、issue #577 stage2、
 * {@link CmsProvisioningBridgeClient}参照)呼び出しの失敗を表す。
 */
public class CmsBridgeException extends RuntimeException {
    public CmsBridgeException(String message, Throwable cause) {
        super(message, cause);
    }
}
