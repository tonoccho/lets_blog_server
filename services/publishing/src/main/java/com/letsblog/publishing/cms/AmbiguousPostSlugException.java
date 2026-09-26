package com.letsblog.publishing.cms;

import java.util.List;

/**
 * 同じスラッグの投稿がWordPress側に複数あり、どれを更新すべきか推測できない場合(issue #1431)。
 * 重複を作らないことを優先し、候補IDを示して投稿を中止する。{@link IllegalStateException}の
 * サブクラスなので、GlobalExceptionHandlerにより409として返る。
 */
public class AmbiguousPostSlugException extends IllegalStateException {
    public AmbiguousPostSlugException(String slug, List<String> candidatePostIds) {
        super("スラッグ '" + slug + "' の投稿がWordPress側に複数あるため、更新対象を決められません。"
                + "候補の投稿ID: " + String.join(", ", candidatePostIds));
    }
}
