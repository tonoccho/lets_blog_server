package com.letsblog.identity.service;

/** プロジェクトに参加していないユーザーを対象に操作しようとしたとき(issue #583でlegacy-apiから移設)。 */
public class ProjectUserNotFoundException extends RuntimeException {
    public ProjectUserNotFoundException(String message) {
        super(message);
    }
}
