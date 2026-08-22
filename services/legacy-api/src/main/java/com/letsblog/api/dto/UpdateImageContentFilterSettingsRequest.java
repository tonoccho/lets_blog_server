package com.letsblog.api.dto;

/**
 * プロジェクトごとの画像生成不適切コンテンツフィルタ設定の更新リクエスト(issue #532)。
 * null(未指定)はアプリ全体のデフォルト(既定は全カテゴリ禁止=true)へのフォールバックを意味する。
 */
public record UpdateImageContentFilterSettingsRequest(
        Boolean blockSexualContent,
        Boolean blockViolentContent,
        Boolean blockDiscriminatoryContent
) {
}
