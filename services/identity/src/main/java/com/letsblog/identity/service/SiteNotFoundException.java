package com.letsblog.identity.service;

/**
 * project-serviceに存在しないサイトを参照したとき(issue #583でlegacy-apiから移設)。
 * サイト本体の所有権はproject-service(#577 stage2)にある。
 */
public class SiteNotFoundException extends RuntimeException {
    public SiteNotFoundException(String message) {
        super(message);
    }
}
