package com.letsblog.publishing.dto;

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
 * @param previewPostId ローカル/テスト環境で非公開投稿として実表示した場合の、作成/更新した
 *                      WordPress投稿ID。次回呼び出し時にRenderSkeletonRequest.existingPreviewPostIdへ
 *                      渡すことで同じ投稿を更新でき、プレビュー用の投稿を積み上げずに済む。
 *                      また、プレビュー終了時にこのIDで投稿を削除できる。従来のスクレイピング&amp;
 *                      スプライス経路(本番環境等)ではnull。
 * @param warning html自体はavailable=trueで返せたものの、一部の付随処理(アイキャッチのアップロード等)が
 *                失敗した場合の非致命的な警告文。呼び出し側でプレビューへ表示する
 *                (Issue: ローカル/テスト環境でアイキャッチが投稿されないのに気付けない問題への対応)。
 *                失敗が無ければnull。
 */
public record ThemeSkeletonResponse(String html, boolean available, String reason, boolean eyecatchSpliced,
        String css, String previewPostId, String warning) {

    /** 従来のスクレイピング&amp;スプライス経路用(previewPostId/warningを持たない)。 */
    public ThemeSkeletonResponse(String html, boolean available, String reason, boolean eyecatchSpliced,
            String css) {
        this(html, available, reason, eyecatchSpliced, css, null, null);
    }

    /** previewPostIdは持つがwarningは無い場合用。 */
    public ThemeSkeletonResponse(String html, boolean available, String reason, boolean eyecatchSpliced,
            String css, String previewPostId) {
        this(html, available, reason, eyecatchSpliced, css, previewPostId, null);
    }
}
