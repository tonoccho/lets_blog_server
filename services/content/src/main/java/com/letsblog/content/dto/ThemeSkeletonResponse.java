package com.letsblog.content.dto;

import java.util.List;

/**
 * PreviewSkeletonFetcherの取得結果(issue #576でcontent-serviceへ移管)。legacy-api側の
 * ArticlePreviewController向けThemeSkeletonResponse(previewPostId/warningを含む7フィールド版)とは
 * 別に、本サービス内部のPreviewSkeletonFetcher/InternalPreviewSkeletonControllerが実際に生成する
 * 5フィールドのみを持つ。previewPostId/warningの付与はlegacy-api側(呼び出し元)の責務。
 *
 * @param html サイトの実テーマDOM構造を保ったまま、タイトル・本文・アイキャッチをプレビュー対象記事の
 *             内容へ差し替えたHTML断片。
 * @param eyecatchSpliced テーマの実マークアップ(既存の&lt;img&gt;要素)へアイキャッチを差し替えられたか。
 * @param css 骨格として実際にナビゲートしたページで読み込まれていたスタイルシートを連結したCSS。
 * @param unreadableStylesheets {@code cssRules}を読めなかった(クロスオリジンでCORSヘッダーが無い等)
 *             stylesheetのhref。cssには含まれないため、呼び出し側がテーマCSS取得と同じ経路で取得して
 *             補完する(issue #1370)。無ければ空リスト。
 */
public record ThemeSkeletonResponse(String html, boolean available, String reason, boolean eyecatchSpliced,
        String css, List<String> unreadableStylesheets) {
}
