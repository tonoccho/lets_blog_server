package com.letsblog.common.net;

/** プロジェクト単位の接続先が接続時検査で拒否された(サーバ内部のネットワーク・禁止アドレス・解決不能)。 */
public class ForbiddenDestinationException extends RuntimeException {

    public ForbiddenDestinationException(String message) {
        super(message);
    }
}
