package com.letsblog.api.cms;

/**
 * {@link CmsAdapter#installWpCli}の結果。既にインストール済みの場合は例外を投げるため、
 * この型は新規インストールに成功した場合のみを表す。
 */
public record WpCliInstallResult(String message) {
}
