package com.letsblog.content.service;

/**
 * [recharts]組み込みタグの記法・属性・表データが不正な場合に投げる。
 * BlogCard/Amazonタグと異なり、記事全体の投稿を拒否する必要があるため
 * (投稿時)、また記事プレビューではレンダリングを中止してエラーメッセージのみを
 * 表示する必要があるため(プレビュー時)、他の組み込みタグのような
 * 「取得失敗時は静かにフォールバック」ではなく例外として扱う。
 */
public class InvalidRechartsTagException extends RuntimeException {
    public InvalidRechartsTagException(String message) {
        super(message);
    }
}
