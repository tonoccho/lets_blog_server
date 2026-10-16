package com.letsblog.plugins.comfyui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 検証済みの画像生成の要求(generations / edits 共通)。 */
record GenerationRequest(String prompt, int n, int width, int height, JsonNode extra) {

    static final int MAX_BATCH_SIZE = 16;
    static final int MIN_SIDE = 64;
    static final int MAX_SIDE = 2048;
    static final int SIDE_STEP = 8;
    static final int DEFAULT_SIDE = 512;

    private static final Pattern SIZE = Pattern.compile("(\\d+)x(\\d+)");

    /**
     * 各項目を検証して作る。{@code n} / {@code size} / {@code extra} は無ければ null(または JSON の null)。
     *
     * @param edits 参照画像つき(/images/edits)か。{@code denoise} はこちらだけが宣言している。
     */
    static GenerationRequest of(JsonNode prompt, JsonNode n, JsonNode size, JsonNode extra, boolean edits)
            throws AdapterException {
        if (prompt == null || !prompt.isTextual() || prompt.asText().isBlank()) {
            throw AdapterException.invalidRequest("prompt は空でない文字列にしてください");
        }
        int count = 1;
        if (n != null && !n.isNull()) {
            if (!n.isIntegralNumber() || n.asLong() < 1 || n.asLong() > MAX_BATCH_SIZE) {
                throw AdapterException.invalidRequest("n は 1 以上 " + MAX_BATCH_SIZE + " 以下の整数にしてください");
            }
            count = n.asInt();
        }
        int[] dimensions = parseSize(size);
        JsonNode extras = extra == null || extra.isNull() ? JsonNodeFactory.instance.objectNode() : extra;
        if (!extras.isObject()) {
            throw AdapterException.invalidRequest("extra はオブジェクトにしてください");
        }
        ExtraParameters.validate(extras, edits);
        return new GenerationRequest(prompt.asText(), count, dimensions[0], dimensions[1], extras);
    }

    private static int[] parseSize(JsonNode size) throws AdapterException {
        if (size == null || size.isNull() || (size.isTextual() && "auto".equals(size.asText()))) {
            return new int[] {DEFAULT_SIDE, DEFAULT_SIDE};
        }
        Matcher matcher = size.isTextual() ? SIZE.matcher(size.asText()) : null;
        if (matcher == null || !matcher.matches()) {
            throw AdapterException.invalidRequest("size は \"<幅>x<高さ>\" または \"auto\" にしてください");
        }
        int width = sideOf(matcher.group(1));
        int height = sideOf(matcher.group(2));
        return new int[] {width, height};
    }

    private static int sideOf(String digits) throws AdapterException {
        long side = digits.length() > 5 ? -1 : Long.parseLong(digits);
        if (side < MIN_SIDE || side > MAX_SIDE || side % SIDE_STEP != 0) {
            throw AdapterException.invalidRequest("size の幅・高さは " + MIN_SIDE + " 以上 " + MAX_SIDE
                    + " 以下の " + SIDE_STEP + " の倍数にしてください");
        }
        return (int) side;
    }

    // ---- 検証済みの extra の値(無ければ既定値) ----

    private JsonNode value(String name) {
        JsonNode node = extra.get(name);
        return node == null || node.isNull() ? null : node;
    }

    String negativePrompt() {
        JsonNode v = value(ExtraParameters.NEGATIVE_PROMPT);
        return v == null ? ExtraParameters.DEFAULT_NEGATIVE_PROMPT : v.asText();
    }

    int steps() {
        JsonNode v = value(ExtraParameters.STEPS);
        return v == null ? ExtraParameters.DEFAULT_STEPS : v.asInt();
    }

    double cfgScale() {
        JsonNode v = value(ExtraParameters.CFG_SCALE);
        return v == null ? ExtraParameters.DEFAULT_CFG_SCALE : v.asDouble();
    }

    String samplerName() {
        JsonNode v = value(ExtraParameters.SAMPLER_NAME);
        return v == null ? ExtraParameters.DEFAULT_SAMPLER : v.asText();
    }

    String scheduler() {
        JsonNode v = value(ExtraParameters.SCHEDULER);
        return v == null ? ExtraParameters.DEFAULT_SCHEDULER : v.asText();
    }

    /** 指定が無ければ null(アダプターがランダムに決める)。 */
    Long seed() {
        JsonNode v = value(ExtraParameters.SEED);
        return v == null ? null : v.asLong();
    }

    /** 指定が無ければ null(アダプターの既定のチェックポイントを使う)。 */
    String checkpoint() {
        JsonNode v = value(ExtraParameters.CHECKPOINT);
        return v == null ? null : v.asText();
    }

    /** 指定が無い・空白なら null(LoRA を使わない)。 */
    String loraName() {
        JsonNode v = value(ExtraParameters.LORA_NAME);
        return v == null || v.asText().isBlank() ? null : v.asText();
    }

    double loraWeight() {
        JsonNode v = value(ExtraParameters.LORA_WEIGHT);
        return v == null ? ExtraParameters.DEFAULT_LORA_WEIGHT : v.asDouble();
    }

    double denoise() {
        JsonNode v = value(ExtraParameters.DENOISE);
        return v == null ? ExtraParameters.DEFAULT_DENOISE : v.asDouble();
    }
}
