package com.letsblog.publishing.cms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * letsblog プラグインが発行した、投稿を作らずに実テーマで表示する署名付きプレビュー URL(issue #1561)。
 *
 * @param url       トークンを含む期限付きの URL
 * @param expiresAt 期限(epoch 秒)
 */
public record SignedPreview(String url, long expiresAt) {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * `wp letsblog preview` が正常終了したときの標準出力(JSONオブジェクト)から取り出す。
     *
     * @throws IllegalArgumentException 出力がJSONオブジェクトでない、またはurl・expires_atが無いとき
     */
    public static SignedPreview fromPreviewOutput(String stdout) {
        JsonNode node;
        try {
            node = stdout == null ? null : OBJECT_MAPPER.readTree(stdout.strip());
        } catch (Exception e) {
            throw new IllegalArgumentException("wp letsblog previewの出力を解釈できません: " + abbreviate(stdout), e);
        }
        if (node == null || !node.isObject() || !node.path("url").isTextual() || node.path("url").asText().isEmpty()
                || !node.path("expires_at").isIntegralNumber()) {
            throw new IllegalArgumentException("wp letsblog previewの出力を解釈できません: " + abbreviate(stdout));
        }
        return new SignedPreview(node.path("url").asText(), node.path("expires_at").asLong());
    }

    private static String abbreviate(String text) {
        String value = text == null ? "" : text.strip();
        return value.length() > 200 ? value.substring(0, 200) + "…" : value;
    }
}
