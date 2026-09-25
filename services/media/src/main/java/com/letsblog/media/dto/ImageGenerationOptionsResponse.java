package com.letsblog.media.dto;

import java.util.List;

public record ImageGenerationOptionsResponse(
        List<String> checkpoints,
        String selectedCheckpoint,
        List<String> samplers,
        List<String> schedulers,
        List<String> loras,
        /** プロジェクトのデフォルト生成サイズ(issue #292)。フォームの初期値表示に使う。 */
        int defaultWidth,
        int defaultHeight,
        /**
         * 生成時に実際に適用される既定値(issue #472)。negative promptが未入力の場合のフォールバック値と、
         * promptへ常に自動追加されるquality promptのサフィックス。{@link com.letsblog.media.service.AiAssistService}
         * の実際の生成ロジックと同じ解決メソッドを使うため、表示値と適用値が乖離しない。
         */
        String defaultNegativePrompt,
        String defaultQualityPrompt
) {
}
