package com.letsblog.media.dto;

/**
 * フォルダ削除の影響範囲(issue #1494)。{@code descendantFolderCount}は自分を含まない子孫フォルダ数、
 * {@code imageCount}は自分と子孫に属し、削除で未分類へ戻る画像の枚数。画像自体は削除されない。
 */
public record GeneratedImageFolderDeleteImpactResponse(long descendantFolderCount, long imageCount) {
}
