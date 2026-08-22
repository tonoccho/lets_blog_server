package com.letsblog.api.domain;

/**
 * WordPress投稿ステータスの正準リスト。Web(投稿ステータス変更)・VS Code拡張(記事の初期ステータス)
 * が独自に選択肢をハードコードしていたものを一本化するために定義する(issue #472)。
 */
public enum PostStatus {
    PUBLISH("publish", "公開"),
    DRAFT("draft", "下書き"),
    PENDING("pending", "レビュー待ち"),
    PRIVATE("private", "非公開");

    private final String value;
    private final String label;

    PostStatus(String value, String label) {
        this.value = value;
        this.label = label;
    }

    public String value() {
        return value;
    }

    public String label() {
        return label;
    }
}
