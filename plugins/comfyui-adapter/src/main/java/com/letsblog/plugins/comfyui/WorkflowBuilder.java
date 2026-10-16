package com.letsblog.plugins.comfyui;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * ComfyUI のワークフロー(/prompt の {@code prompt})の組み立て。移す前の中核の
 * {@code ComfyUiClient#buildWorkflow} と同じ形(txt2img / img2img)。
 */
final class WorkflowBuilder {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private WorkflowBuilder() {
    }

    /**
     * @param referenceName アップロード済みの参照画像のファイル名。null なら txt2img、あれば img2img。
     * @param checkpoint 使うチェックポイント(指定が無ければアダプターの既定)
     * @param seed KSampler の seed(実値)
     */
    static ObjectNode build(GenerationRequest request, String checkpoint, long seed, String referenceName) {
        ObjectNode graph = F.objectNode();
        node(graph, "4", "CheckpointLoaderSimple").put("ckpt_name", checkpoint);

        String latentRef;
        double denoise;
        if (referenceName == null) {
            ObjectNode latent = node(graph, "5", "EmptyLatentImage");
            latent.put("width", request.width()).put("height", request.height()).put("batch_size", request.n());
            latentRef = "5";
            denoise = 1.0;
        } else {
            node(graph, "11", "LoadImage").put("image", referenceName);
            ObjectNode scale = node(graph, "12", "ImageScale");
            link(scale, "image", "11", 0);
            scale.put("upscale_method", "lanczos").put("width", request.width())
                    .put("height", request.height()).put("crop", "center");
            ObjectNode encode = node(graph, "13", "VAEEncode");
            link(encode, "pixels", "12", 0);
            link(encode, "vae", "4", 2);
            latentRef = "13";
            // VAEEncode の潜在画像は 1 枚分。n 枚を作るには枚数分に複製する。
            if (request.n() > 1) {
                ObjectNode repeat = node(graph, "14", "RepeatLatentBatch");
                link(repeat, "samples", "13", 0);
                repeat.put("amount", request.n());
                latentRef = "14";
            }
            denoise = request.denoise();
        }

        String modelRef = "4";
        String clipRef = "4";
        if (request.loraName() != null) {
            ObjectNode lora = node(graph, "10", "LoraLoader");
            lora.put("lora_name", request.loraName())
                    .put("strength_model", request.loraWeight()).put("strength_clip", request.loraWeight());
            link(lora, "model", "4", 0);
            link(lora, "clip", "4", 1);
            modelRef = "10";
            clipRef = "10";
        }

        ObjectNode positive = node(graph, "6", "CLIPTextEncode");
        positive.put("text", request.prompt());
        link(positive, "clip", clipRef, 1);
        ObjectNode negative = node(graph, "7", "CLIPTextEncode");
        negative.put("text", request.negativePrompt());
        link(negative, "clip", clipRef, 1);

        ObjectNode sampler = node(graph, "3", "KSampler");
        sampler.put("seed", seed).put("steps", request.steps()).put("cfg", request.cfgScale())
                .put("sampler_name", request.samplerName()).put("scheduler", request.scheduler())
                .put("denoise", denoise);
        link(sampler, "model", modelRef, 0);
        link(sampler, "positive", "6", 0);
        link(sampler, "negative", "7", 0);
        link(sampler, "latent_image", latentRef, 0);

        ObjectNode decode = node(graph, "8", "VAEDecode");
        link(decode, "samples", "3", 0);
        link(decode, "vae", "4", 2);

        ObjectNode save = node(graph, "9", "SaveImage");
        save.put("filename_prefix", "letsblog");
        link(save, "images", "8", 0);
        return graph;
    }

    /** グラフにノードを足し、その {@code inputs} を返す。 */
    private static ObjectNode node(ObjectNode graph, String id, String classType) {
        ObjectNode node = graph.putObject(id);
        node.put("class_type", classType);
        return node.putObject("inputs");
    }

    private static void link(ObjectNode inputs, String name, String fromNode, int output) {
        inputs.putArray(name).add(fromNode).add(output);
    }
}
