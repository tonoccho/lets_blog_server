package com.letsblog.publishing.service;

/** project-serviceの内部ブリッジがプロジェクト未登録(404)を返した場合に投げる(issue #707)。 */
public class ProjectNotFoundException extends RuntimeException {
    public ProjectNotFoundException(String message) {
        super(message);
    }
}
