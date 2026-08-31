package com.letsblog.media.service;

/**
 * project-serviceに存在しないプロジェクトを参照したとき(issue #583でlegacy-apiから移設)。
 * プロジェクト本体の所有権はproject-service(#577 stage2)にある。
 */
public class ProjectNotFoundException extends RuntimeException {
    public ProjectNotFoundException(String message) {
        super(message);
    }
}
