package com.letsblog.platform.dto;

/**
 * legacy-apiから移設(issue #695、C10-3)。ダッシュボードに表示する接続サービス(DB・外部連携)の
 * 稼働状況(正常/警告/エラーの3値のみ)。
 */
public record ConnectedServiceStatusResponse(String id, String name, Status status) {

    public enum Status {
        NORMAL,
        WARNING,
        ERROR
    }
}
