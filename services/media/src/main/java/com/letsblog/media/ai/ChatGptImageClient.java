package com.letsblog.media.ai;

import com.letsblog.common.client.ExternalCallLoggingInterceptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.letsblog.media.config.LegacyJacksonRestClientConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
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
 *
 * <p>参照画像({@link ComfyUiGenerationParams#referenceImage()})があるときは、編集指示型の
 * POST {baseUrl}/images/edits へmultipart(image/prompt/model/n/size)で送る(issue #1602)。
 * 変化の強さ(denoise)はgpt-image-1に無いので送らない。参照画像が無いときは従来どおりgenerationsを呼ぶ。
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
        builder.requestInterceptor(new ExternalCallLoggingInterceptor("openai-image"));
        this.client = builder.build();
        this.configProvider = configProvider;
    }

    @Override
    public List<ComfyUiImage> generateImage(ComfyUiGenerationParams params) {
        String apiKey = configProvider.chatGptApiKey(params.projectId());
        if (apiKey == null || apiKey.isBlank()) {
            throw new AiServiceException(
                    "ChatGPTのAPIキーが設定されていません。このプロジェクトでAPIキーを設定してください。", null);
        }

        boolean edit = params.referenceImage() != null;
        int batchSize = params.batchSize() != null ? params.batchSize() : 1;
        String size = resolveSize(params.width(), params.height());

        try {
            JsonNode response;
            if (edit) {
                response = client.post()
                        .uri(configProvider.chatGptBaseUrl() + "/images/edits")
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .header("Authorization", "Bearer " + apiKey)
                        .body(editForm(params, batchSize, size))
                        .retrieve()
                        .body(JsonNode.class);
            } else {
                JsonNode body = JsonNodeFactory.instance.objectNode()
                        .put("model", MODEL)
                        .put("prompt", params.prompt())
                        .put("n", batchSize)
                        .put("size", size);
                response = client.post()
                        .uri(configProvider.chatGptBaseUrl() + "/images/generations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + apiKey)
                        .body(body)
                        .retrieve()
                        .body(JsonNode.class);
            }

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
                    "ChatGPT画像" + (edit ? "編集(images/edits)" : "生成") + "の呼び出しに失敗しました: "
                            + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            throw new AiServiceException(
                    "ChatGPT画像" + (edit ? "編集(images/edits)" : "生成")
                            + "中にエラーが発生しました（タイムアウトまたはネットワークエラーの可能性があります）: "
                            + e.getMessage(), e);
        }
    }

    /** /images/editsのmultipartフォーム。画像はファイルパートとして、型と拡張子を添えて送る。 */
    private MultiValueMap<String, Object> editForm(ComfyUiGenerationParams params, int batchSize, String size) {
        ReferenceImage reference = params.referenceImage();
        String mimeType = reference.mimeType() != null ? reference.mimeType() : "image/png";
        String filename = "reference." + extensionOf(mimeType);
        HttpHeaders imageHeaders = new HttpHeaders();
        imageHeaders.setContentType(MediaType.parseMediaType(mimeType));
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("image", new HttpEntity<>(new ByteArrayResource(reference.data()) {
            @Override
            public String getFilename() {
                return filename;
            }
        }, imageHeaders));
        form.add("prompt", params.prompt());
        form.add("model", MODEL);
        form.add("n", String.valueOf(batchSize));
        form.add("size", size);
        return form;
    }

    private static String extensionOf(String mimeType) {
        return switch (mimeType) {
            case "image/jpeg" -> "jpg";
            case "image/webp" -> "webp";
            default -> "png";
        };
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
