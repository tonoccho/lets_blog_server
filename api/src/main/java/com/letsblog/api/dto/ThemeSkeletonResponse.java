package com.letsblog.api.dto;

/**
 * @param html サイトの実テーマDOM構造(タイトル/カテゴリ/日付/アイキャッチ等)を保ったまま、
 *             タイトル・本文・アイキャッチをプレビュー対象記事の内容へ差し替えたHTML断片。
 * @param eyecatchSpliced テーマの実マークアップ(既存の`<img>`要素)へアイキャッチを差し替えられたか。
 *                        falseの場合、アイキャッチはテーマ構造の外側へ簡易的に挿入されている(または非表示)。
 * @param css 骨格として実際にナビゲートした投稿ページで読み込まれていたスタイルシート(および
 *            インライン&lt;style&gt;)を連結したCSS。ナビゲーションに成功していれば、本文の差し替え位置を
 *            特定できずhtmlがavailable=falseの場合でも収集される。ナビゲーション自体に失敗した場合は空文字。
 *            トップページのみを対象とする{@code /theme-css}では収集できない、投稿ページ限定で
 *            読み込まれるCSS(is_single()等)を補うためのもの。呼び出し側でトップページのCSSと
 *            マージして使うことを想定している。
 */
public record ThemeSkeletonResponse(String html, boolean available, String reason, boolean eyecatchSpliced,
        String css) {
}
