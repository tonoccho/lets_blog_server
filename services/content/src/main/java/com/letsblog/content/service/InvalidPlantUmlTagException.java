package com.letsblog.content.service;

/**
 * [plantuml]組み込みタグの記法・レンダリングが不正な場合に投げる(issue #344)。
 * [recharts]タグ(issue #340)と同じ方針で、他の組み込みタグのような
 * 「取得失敗時は静かにフォールバック」ではなく例外として扱う:
 * - プレビューはこれを捕捉し、レンダリングを中止してエラーメッセージのみを表示する。
 * - 投稿はこれを未捕捉のまま伝播させ、投稿自体を拒否する(GlobalExceptionHandlerが400として返す)。
 */
public class InvalidPlantUmlTagException extends RuntimeException {
    public InvalidPlantUmlTagException(String message) {
        super(message);
    }

    public InvalidPlantUmlTagException(String message, Throwable cause) {
        super(message, cause);
    }
}
