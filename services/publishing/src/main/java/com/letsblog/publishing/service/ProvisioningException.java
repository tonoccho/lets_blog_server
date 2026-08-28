package com.letsblog.publishing.service;

/** {@link ProvisioningService#provisionSite}がCMSアダプタの解決自体に失敗した場合に投げる致命的エラー。 */
public class ProvisioningException extends RuntimeException {
    public ProvisioningException(String message, Throwable cause) {
        super(message, cause);
    }
}
