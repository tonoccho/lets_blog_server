package com.letsblog.plugins.claude;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * OpenAI 互換の {@code /chat/completions} の要求(CONTRACTS.md §1.1)を、Anthropic Messages API の要求へ翻訳する。
 *
 * <p>services/ai の {@code LlmClient#claudeUserMessage} を移したもの。画像ありは image ブロックを text ブロックの前に置く。
 */
final class MessageTranslator {

    /** Messages API は max_tokens が必須。契約は送らないので、現行の中核と同じ値を既定にする。 */
    static final int MAX_TOKENS = 4096;

    private static final String BASE64_MARKER = ";base64,";

    private MessageTranslator() {
    }

    /** 契約の要求本文を検査し、Anthropic の要求本文にして返す。 */
    static ObjectNode toAnthropicRequest(JsonNode request) throws AdapterException {
        String model = request.path("model").asText("");
        if (model.isBlank()) {
            throw AdapterException.invalidRequest("model が必要です");
        }
        JsonNode messages = request.path("messages");
        if (!messages.isArray() || messages.isEmpty()) {
            throw AdapterException.invalidRequest("messages には role が user の要素が 1 件必要です");
        }
        JsonNode content = messages.get(0).path("content");
        ObjectNode message = JsonNodeFactory.instance.objectNode().put("role", "user");
        if (content.isTextual()) {
            message.put("content", content.asText());
        } else {
            message.set("content", blocks(content));
        }
        ObjectNode body = JsonNodeFactory.instance.objectNode()
                .put("model", model)
                .put("max_tokens", MAX_TOKENS);
        body.putArray("messages").add(message);
        return body;
    }

    private static ArrayNode blocks(JsonNode content) throws AdapterException {
        if (!content.isArray() || content.isEmpty()) {
            throw AdapterException.invalidRequest("content は文字列、または text と image_url の配列にしてください");
        }
        ArrayNode images = JsonNodeFactory.instance.arrayNode();
        ArrayNode texts = JsonNodeFactory.instance.arrayNode();
        for (JsonNode part : content) {
            switch (part.path("type").asText("")) {
                case "text" -> {
                    if (!part.path("text").isTextual()) {
                        throw AdapterException.invalidRequest("text の要素には text が必要です");
                    }
                    texts.addObject().put("type", "text").put("text", part.get("text").asText());
                }
                case "image_url" -> images.add(imageBlock(part.path("image_url").path("url")));
                default -> throw AdapterException.invalidRequest("content の要素は text か image_url にしてください");
            }
        }
        return images.addAll(texts);
    }

    private static ObjectNode imageBlock(JsonNode url) throws AdapterException {
        String value = url.asText("");
        int marker = value.indexOf(BASE64_MARKER);
        if (!value.startsWith("data:") || marker < 0) {
            throw AdapterException.invalidRequest("image_url.url は data:<MIME>;base64,<データ> の形にしてください");
        }
        ObjectNode block = JsonNodeFactory.instance.objectNode().put("type", "image");
        block.putObject("source")
                .put("type", "base64")
                .put("media_type", value.substring("data:".length(), marker))
                .put("data", value.substring(marker + BASE64_MARKER.length()));
        return block;
    }
}
