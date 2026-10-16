package com.letsblog.plugins.comfyui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Optional;

/**
 * ComfyUI 固有の追加パラメータ(CONTRACTS.md §2.3)の宣言と検証。範囲と既定値は、移す前の中核の
 * {@code AiImageRequest} の検証({@code @Min} / {@code @Max} / {@code @DecimalMax})と
 * {@code ComfyUiGenerationParams.withDefaults} に合わせる。宣言(/capabilities)と検証は同じ表から作る。
 */
final class ExtraParameters {

    static final String NEGATIVE_PROMPT = "negativePrompt";
    static final String STEPS = "steps";
    static final String CFG_SCALE = "cfgScale";
    static final String SAMPLER_NAME = "samplerName";
    static final String SCHEDULER = "scheduler";
    static final String SEED = "seed";
    static final String CHECKPOINT = "checkpoint";
    static final String LORA_NAME = "loraName";
    static final String LORA_WEIGHT = "loraWeight";
    static final String DENOISE = "denoise";

    static final String DEFAULT_NEGATIVE_PROMPT = "low quality, blurry, watermark, text";
    static final int DEFAULT_STEPS = 20;
    static final double DEFAULT_CFG_SCALE = 7.0;
    static final String DEFAULT_SAMPLER = "euler";
    static final String DEFAULT_SCHEDULER = "normal";
    static final double DEFAULT_LORA_WEIGHT = 1.0;
    /** 参照画像つき(edits)で変化の強さを指定されなかったときの denoise。 */
    static final double DEFAULT_DENOISE = 0.6;
    /** ComfyUI の KSampler が扱う seed の上限(非負 32bit)。 */
    static final long MAX_SEED = 4294967295L;

    /** 種類。 */
    enum Type {
        INTEGER("integer"), NUMBER("number"), STRING("string"), ENUM("enum");

        private final String wire;

        Type(String wire) {
            this.wire = wire;
        }
    }

    /** 1 つの追加パラメータの宣言。{@code min} / {@code max} は数値型だけ、{@code defaultValue} は無ければ null。 */
    record Spec(String name, String label, Type type, Double min, Double max, Object defaultValue, boolean editsOnly) {

        boolean appliesTo(boolean edits) {
            return edits || !editsOnly;
        }
    }

    static final List<Spec> SPECS = List.of(
            new Spec(NEGATIVE_PROMPT, "ネガティブプロンプト", Type.STRING, null, null, DEFAULT_NEGATIVE_PROMPT, false),
            new Spec(STEPS, "ステップ数", Type.INTEGER, 1.0, 150.0, DEFAULT_STEPS, false),
            new Spec(CFG_SCALE, "CFG スケール", Type.NUMBER, 0.0, 30.0, DEFAULT_CFG_SCALE, false),
            new Spec(SAMPLER_NAME, "サンプラー", Type.ENUM, null, null, DEFAULT_SAMPLER, false),
            new Spec(SCHEDULER, "スケジューラー", Type.ENUM, null, null, DEFAULT_SCHEDULER, false),
            new Spec(SEED, "シード", Type.INTEGER, 0.0, (double) MAX_SEED, null, false),
            new Spec(CHECKPOINT, "チェックポイント", Type.ENUM, null, null, null, false),
            new Spec(LORA_NAME, "LoRA", Type.ENUM, null, null, null, false),
            new Spec(LORA_WEIGHT, "LoRA の重み", Type.NUMBER, 0.0, 2.0, DEFAULT_LORA_WEIGHT, false),
            new Spec(DENOISE, "変化の強さ(参照画像つき)", Type.NUMBER, 0.0, 1.0, DEFAULT_DENOISE, true));

    private ExtraParameters() {
    }

    /** /capabilities の {@code extraParameters[]} の 1 要素。{@code options} は選択肢のある項目だけ。 */
    static ObjectNode declaration(Spec spec, Object defaultValue, List<String> options) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("name", spec.name()).put("label", spec.label()).put("type", spec.type().wire);
        if (spec.min() != null) {
            if (spec.type() == Type.INTEGER) {
                node.put("min", spec.min().longValue()).put("max", spec.max().longValue());
            } else {
                node.put("min", spec.min()).put("max", spec.max());
            }
        }
        if (options != null) {
            var array = node.putArray("options");
            options.forEach(array::add);
        }
        if (defaultValue instanceof Integer i) {
            node.put("default", i);
        } else if (defaultValue instanceof Double d) {
            node.put("default", d);
        } else if (defaultValue instanceof String s) {
            node.put("default", s);
        }
        var appliesTo = node.putArray("appliesTo");
        if (!spec.editsOnly()) {
            appliesTo.add("generations");
        }
        appliesTo.add("edits");
        return node;
    }

    /** {@code extra} の各項目を宣言に照らして検証する。宣言が無い項目・型や範囲の外・この経路に無い項目は 400。 */
    static void validate(JsonNode extra, boolean edits) throws AdapterException {
        var names = extra.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            Optional<Spec> spec = SPECS.stream().filter(s -> s.name().equals(name)).findFirst();
            if (spec.isEmpty() || !spec.get().appliesTo(edits)) {
                throw AdapterException.invalidRequest("extra." + name + " は宣言されていない追加パラメータです");
            }
            JsonNode value = extra.get(name);
            if (!value.isNull()) {
                check(spec.get(), value);
            }
        }
    }

    private static void check(Spec spec, JsonNode value) throws AdapterException {
        boolean typeOk = switch (spec.type()) {
            case INTEGER -> value.isIntegralNumber();
            case NUMBER -> value.isNumber();
            case STRING, ENUM -> value.isTextual();
        };
        if (!typeOk) {
            throw AdapterException.invalidRequest("extra." + spec.name() + " は " + spec.type().wire + " にしてください");
        }
        if (spec.min() != null && (value.asDouble() < spec.min() || value.asDouble() > spec.max())) {
            throw AdapterException.invalidRequest(
                    "extra." + spec.name() + " は " + spec.min() + " 以上 " + spec.max() + " 以下にしてください");
        }
    }
}
