package com.letsblog.media.ai;

import java.util.List;

/**
 * 画像生成AI(ComfyUI/ChatGPT)を抽象化するインターフェース(issue #531)。
 * ComfyUiGenerationParamsはautomatic1111相当の全パラメータを持つが、ComfyUiClientが全項目を使用するのに対し
 * ChatGptImageClientはprompt/width/height/batchSizeのみを使用し、それ以外(steps/cfgScale/samplerName/
 * scheduler/checkpoint/loraName/loraWeight/negativePrompt/seed)はChatGPT画像生成APIが対応していないため無視する。
 */
public interface ImageGenerationProvider {
    List<ComfyUiImage> generateImage(ComfyUiGenerationParams params);
}
