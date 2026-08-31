package com.letsblog.identity.service;

/**
 * project-serviceに存在しないプロジェクトを参照したとき(issue #583でlegacy-apiから移設)。
 * プロジェクト本体の所有権はproject-service(#577 stage2)にあり、identity-serviceは
 * {@link com.letsblog.identity.client.ProjectServiceClient}経由で照会するだけである。
 */
public class ProjectNotFoundException extends RuntimeException {
    public ProjectNotFoundException(String message) {
        super(message);
    }
}
