package com.letsblog.project.dto;

/** X へのテスト投稿の結果(issue #1574)。失敗の理由はプラグインが履歴に残したものと同じ。 */
public record XTestResult(boolean success, String error) {
}
