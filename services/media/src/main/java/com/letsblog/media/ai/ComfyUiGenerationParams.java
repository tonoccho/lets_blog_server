package com.letsblog.media.ai;

/**
 * automatic1111相当のパラメータでComfyUIのtxt2imgワークフローを組み立てるための入力値。
 * checkpoint/loraNameがnullの場合の解決(プロジェクト選択値やグローバルデフォルトへのフォールバック)は
 * 呼び出し側(AiAssistService等)の責務とし、ここではワークフロー構築に必要な値をそのまま保持する。
 */
public record ComfyUiGenerationParams(
        String prompt,
        String negativePrompt,
        Integer steps,
        Double cfgScale,
        String samplerName,
        String scheduler,
        Long seed,
        Integer width,
        Integer height,
        Integer batchSize,
        String checkpoint,
        String loraName,
        Double loraWeight,
        /** 接続先(ComfyUI)をプロジェクト単位で解決するためのプロジェクト。nullならシステム設定(issue #1503)。 */
        Long projectId
) {
    /** projectIdを持たない呼び出し(システム設定の接続先を使う)向け。 */
    public ComfyUiGenerationParams(
            String prompt, String negativePrompt, Integer steps, Double cfgScale, String samplerName,
            String scheduler, Long seed, Integer width, Integer height, Integer batchSize, String checkpoint,
            String loraName, Double loraWeight) {
        this(prompt, negativePrompt, steps, cfgScale, samplerName, scheduler, seed, width, height, batchSize,
                checkpoint, loraName, loraWeight, null);
    }

    public static ComfyUiGenerationParams withDefaults(String prompt) {
        return new ComfyUiGenerationParams(
                prompt,
                "low quality, blurry, watermark, text",
                20, 7.0, "euler", "normal", null, 512, 512, 1,
                null,
                null, null
        );
    }
}
