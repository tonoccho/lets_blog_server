package com.letsblog.api.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * OllamaClientの回帰テスト。Qwen3等の推論モデルが出力に混入させる<think>...</think>ブロックの
 * 除去ロジックを中心に検証する(実HTTP呼び出しはモックせず、ロジック単体を検証する)。
 */
class OllamaClientTest {

    private final OllamaClient client =
            new OllamaClient("http://ollama:11434", "qwen3:14b", new ObjectMapper());

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
