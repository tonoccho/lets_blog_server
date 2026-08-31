package com.letsblog.analytics.service;

/**
 * 指定されたprojectIdがlegacy-api(project-serviceが未抽出のため引き続きlegacy-apiが所有)に
 * 存在しないことを表す。{@link com.letsblog.analytics.client.ProjectBridgeClient}経由の
 * プロジェクト存在確認(AnalyticsBridgeController、legacy-apiが所有)がHTTP 404を返した場合に
 * ここへマッピングする(legacy-apiのProjectNotFoundExceptionと同じ404マッピング)。
 */
public class ProjectNotFoundException extends RuntimeException {
    public ProjectNotFoundException(String message) {
        super(message);
    }
}
