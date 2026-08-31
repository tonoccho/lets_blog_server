package com.letsblog.identity.client;

/** publishing-serviceへの内部ブリッジ呼び出しが失敗したとき(issue #583でlegacy-apiから移設)。 */
public class PublishingServiceException extends RuntimeException {
    public PublishingServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
