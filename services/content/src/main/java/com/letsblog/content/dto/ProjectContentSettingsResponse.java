package com.letsblog.content.dto;

/**
 * プロジェクト単位のコンテンツ設定({@code project_content_settings})。
 *
 * <p>issue #583以前、cssSelectorPrefix の更新は legacy-api の {@code ProjectController} にあり、
 * <b>プロジェクト全体</b>({@code ProjectResponse})を返していた。所有権が content-service に
 * あることを踏まえて #583 でこちらへ移したが、プロジェクト本体は project-service(#577 stage2)の
 * ものなので組み立てられない。<b>更新した設定そのもの</b>を返す。
 *
 * <p>{@code null} は「未設定」を意味する。実際にレンダリングで使う値は
 * {@code ProjectContentSettingsService#resolveCssSelectorPrefix} がプロジェクトの slug へ
 * フォールバックして決める。
 */
public record ProjectContentSettingsResponse(String cssSelectorPrefix) {
}
