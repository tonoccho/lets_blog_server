package com.letsblog.media.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 画像生成プロバイダの識別と、1回に作れる枚数の上限(issue #1102)。
 *
 * <p>上限をここに持たせているのは、{@code AiImageRequest}の{@code @Max(16)}が
 * 全プロバイダ共通の上限しか表現できず、実際にどのプロバイダを使うかは
 * プロジェクト設定から実行時に決まるためである。
 */
@DisplayName("media-service: 画像生成プロバイダの上限と識別(issue #1102)")
class ImageProviderTest {

    @Test
    void プロバイダごとに1回に作れる枚数の上限を持つ() {
        assertEquals(16, ImageProvider.COMFYUI.maxBatchSize(),
                "ComfyUIはEmptyLatentImageのbatch_sizeで16枚まで作れる");
        assertEquals(10, ImageProvider.CHATGPT.maxBatchSize(),
                "OpenAIの画像生成APIのnは最大10");
    }

    @Test
    void CHATGPTの上限はCOMFYUIより小さい() {
        assertTrue(ImageProvider.CHATGPT.maxBatchSize() < ImageProvider.COMFYUI.maxBatchSize());
    }

    @Test
    void 未設定はnullとして返し呼び出し側が既定値を選べるようにする() {
        assertNull(ImageProvider.fromString(null));
        assertNull(ImageProvider.fromString(""));
        assertNull(ImageProvider.fromString("   "));
    }

    @Test
    void 大文字小文字と前後の空白を無視して解釈する() {
        assertEquals(ImageProvider.COMFYUI, ImageProvider.fromString("comfyui"));
        assertEquals(ImageProvider.CHATGPT, ImageProvider.fromString("  ChatGPT  "));
    }

    @Test
    void 知らない名前は値を添えて拒否する() {
        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class, () -> ImageProvider.fromString("midjourney"));
        assertTrue(e.getMessage().contains("midjourney"), e.getMessage());
    }
}
