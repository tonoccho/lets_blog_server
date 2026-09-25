package com.letsblog.media.dto;

import com.letsblog.media.domain.ProjectImageSettings;

/**
 * プロジェクト単位の画像生成設定({@code project_image_settings})。
 *
 * <p>issue #583以前、これらの設定を更新する5本のエンドポイントはlegacy-apiにあり、
 * <b>プロジェクト全体</b>({@code ProjectResponse})を返していた。所有権がmedia-serviceへ
 * 移ったことでプロジェクト全体は組み立てられなくなるため、<b>更新した設定そのもの</b>を返す。
 *
 * <p>Web側は戻り値を使わず({@code apps/web/src/app/projects/[id]/actions.ts}は{@code await}して
 * 破棄し{@code revalidatePath}する)、保存後は再取得しているため、この変更で壊れる画面は無い。
 * ただしその再取得側が値を表示できていない既存の不具合があり、そちらは #913 で扱う。
 *
 * <p>{@code null}は「プロジェクト単位の上書きなし」= アプリ全体の既定値を使う、を意味する
 * ({@code ProjectImageDefaultsResolver}が解決する)。
 */
public record ProjectImageSettingsResponse(
        Long projectId,
        String imageProvider,
        String comfyuiCheckpoint,
        String defaultNegativePrompt,
        String defaultQualityPrompt,
        Integer defaultGeneratedImageWidth,
        Integer defaultGeneratedImageHeight,
        Integer defaultArticleImageLongEdgePx,
        Boolean blockSexualContent,
        Boolean blockViolentContent,
        Boolean blockDiscriminatoryContent
) {
    public static ProjectImageSettingsResponse from(ProjectImageSettings settings) {
        return new ProjectImageSettingsResponse(
                settings.getProjectId(),
                settings.getImageProvider(),
                settings.getComfyuiCheckpoint(),
                settings.getDefaultNegativePrompt(),
                settings.getDefaultQualityPrompt(),
                settings.getDefaultGeneratedImageWidth(),
                settings.getDefaultGeneratedImageHeight(),
                settings.getDefaultArticleImageLongEdgePx(),
                settings.getBlockSexualContent(),
                settings.getBlockViolentContent(),
                settings.getBlockDiscriminatoryContent());
    }
}
