package com.letsblog.common.net;

/** ネットワークインタフェースに付いたアドレス(4または16バイト)とプレフィックス長。 */
public record InterfaceAddr(byte[] address, int prefixLength) {
}
