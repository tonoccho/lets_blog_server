package com.letsblog.api.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * LlmClientの回帰テスト。推論モデル経由で出力に混入しうる<think>...</think>ブロックの
 * 除去ロジックを中心に検証する(実HTTP呼び出しはモックせず、ロジック単体を検証する)。
 */
class LlmClientTest {

    private final LlmClient client = new LlmClient("https://api.openai.com/v1", "test-key", "gpt-4o-mini", 120L);

    @Test
    void stripThinkingBlocks_thinkブロックを除去する() {
        String raw = "<think>この記事はADHDについてで、タイトルは{ダミー}にしよう</think>{\"title\":\"タイトル\"}";

        String result = client.stripThinkingBlocks(raw);

        assertEquals("{\"title\":\"タイトル\"}", result);
    }

    @Test
    void stripThinkingBlocks_複数行のthinkブロックにも対応する() {
        String raw = "<think>\n複数行の思考\n{ここにも波括弧}\n</think>\n{\"title\":\"タイトル\"}";

        String result = client.stripThinkingBlocks(raw);

        assertEquals("{\"title\":\"タイトル\"}", result);
    }

    @Test
    void stripThinkingBlocks_thinkブロックがなければそのまま返す() {
        String raw = "{\"title\":\"タイトル\"}";

        String result = client.stripThinkingBlocks(raw);

        assertEquals("{\"title\":\"タイトル\"}", result);
    }
}
