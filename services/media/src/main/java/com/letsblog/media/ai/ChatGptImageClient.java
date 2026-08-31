package com.letsblog.media.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.letsblog.media.config.LegacyJacksonRestClientConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * OpenAIの画像生成API(POST {baseUrl}/images/generations)を呼び出すクライアント(issue #531)。
 * モデルはv1では gpt-image-1 固定とし、プロジェクト単位のモデル選択は行わない。
 * ComfyUiGenerationParamsのうちprompt/width/height/batchSizeのみを使用し、ComfyUI固有の
 * steps/cfgScale/samplerName/scheduler/checkpoint/loraName/loraWeight/negativePrompt/seedは
 * ChatGPT画像生成APIが対応していないため無視する。
 */
@Component
public class ChatGptImageClient implements ImageGenerationProvider {

    private static final String MODEL = "gpt-image-1";

    private final RestClient client;
    private final ImageGenerationConfigProvider configProvider;

    @Autowired
    public ChatGptImageClient(ImageGenerationConfigProvider configProvider) {
        this(RestClient.builder(), configProvider);
    }

    /** テスト専用: MockRestServiceServerを介せるようRestClient.Builderを直接受け取るコンストラクタ。 */
    ChatGptImageClient(RestClient.Builder builder, ImageGenerationConfigProvider configProvider) {
        LegacyJacksonRestClientConfig.preferJackson2(builder);
        this.client = builder.build();
        this.configProvider = configProvider;
    }

    @Override
    public List<ComfyUiImage> generateImage(ComfyUiGenerationParams params) {
        String apiKey = configProvider.chatGptApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new AiServiceException(
                    "ChatGPTの画像生成APIキーが設定されていません。Web管理画面のシステム設定で設定してください。", null);
        }

        int batchSize = params.batchSize() != null ? params.batchSize() : 1;
        JsonNode body = JsonNodeFactory.instance.objectNode()
                .put("model", MODEL)
                .put("prompt", params.prompt())
                .put("n", batchSize)
                .put("size", resolveSize(params.width(), params.height()));

        try {
            JsonNode response = client.post()
                    .uri(configProvider.chatGptBaseUrl() + "/images/generations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + apiKey)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);

            List<ComfyUiImage> images = new ArrayList<>();
            int index = 1;
            for (JsonNode item : response.get("data")) {
                byte[] data = Base64.getDecoder().decode(item.get("b64_json").asText());
                images.add(new ComfyUiImage("chatgpt_" + index + ".png", data, "image/png"));
                index++;
            }
            return images;
        } catch (RestClientResponseException e) {
            throw new AiServiceException(
                    "ChatGPT画像生成の呼び出しに失敗しました: " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            throw new AiServiceException(
                    "ChatGPT画像生成中にエラーが発生しました（タイムアウトまたはネットワークエラーの可能性があります）: " + e.getMessage(), e);
        }
    }

    /**
     * gpt-image-1はsizeとして"1024x1024"/"1024x1536"/"1536x1024"/"auto"のみ受け付けるため、
     * ComfyUI用に自由指定されたwidth/heightを最も近い対応サイズへ丸める。
     */
    private String resolveSize(Integer width, Integer height) {
        if (width == null || height == null) {
            return "auto";
        }
        if (width.equals(height)) {
            return "1024x1024";
        }
        return width > height ? "1536x1024" : "1024x1536";
    }
}
