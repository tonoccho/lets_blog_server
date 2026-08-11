package com.letsblog.api.dto;

/**
 * @param html サイトの実テーマDOM構造(タイトル/カテゴリ/日付/アイキャッチ等)を保ったまま、
 *             タイトル・本文・アイキャッチをプレビュー対象記事の内容へ差し替えたHTML断片。
 * @param eyecatchSpliced テーマの実マークアップ(既存の`<img>`要素)へアイキャッチを差し替えられたか。
 *                        falseの場合、アイキャッチはテーマ構造の外側へ簡易的に挿入されている(または非表示)。
 */
public record ThemeSkeletonResponse(String html, boolean available, String reason, boolean eyecatchSpliced) {
}
