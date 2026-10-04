package com.letsblog.media.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.media.client.AiGenerationClient;
import com.letsblog.media.domain.GeneratedImage;
import com.letsblog.media.repository.GeneratedImageRepository;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * アップロードした画像を、プロジェクトのLLM設定で画像内容から読み取ってAIタグを付ける(issue #1600)。
 *
 * <p>生成画像のタグ提案(#281、{@code ImageGenerationService#suggestImageTagsJson})は生成プロンプトの
 * 文字列から推定するが、アップロード画像にはプロンプトが無いので画像そのものを渡す。
 * タグの言語・件数・出力形式は#281のテンプレートに揃える。
 *
 * <p>補助機能なので、AIの失敗・タイムアウト・未設定・画像入力に非対応のモデル(ai-serviceがnullを返す)の
 * いずれでも例外を外へ出さず、タグなしのまま終える。アップロード応答の後に別スレッド
 * ({@code imageTaggingExecutor})で実行する。{@code @Async}は自己呼び出しに効かないため、
 * 呼び出し元({@link GeneratedImageUploadService})は別のBeanとして呼ぶ。
 */
@Service
public class UploadedImageTagService {

    private static final Logger log = LoggerFactory.getLogger(UploadedImageTagService.class);

    /** #281の{@code IMAGE_TAGS_PROMPT_TEMPLATE}と同じ件数・言語・JSON形式。画像を直接見る点だけが違う。 */
    static final String PROMPT = """
            添付の画像を見て、この画像を検索・分類しやすくするための
            短い日本語タグを3〜5個程度提案してください。
            出力は必ず次のJSON形式のみとし、他の文章は一切含めないでください。

            {"tags": ["タグ1", "タグ2", "タグ3"]}
            """;

    private final AiGenerationClient aiGenerationClient;
    private final GeneratedImageRepository generatedImageRepository;
    private final ObjectMapper objectMapper;

    public UploadedImageTagService(
            AiGenerationClient aiGenerationClient, GeneratedImageRepository generatedImageRepository,
            ObjectMapper objectMapper) {
        this.aiGenerationClient = aiGenerationClient;
        this.generatedImageRepository = generatedImageRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * @param imageData アップロード時に変換・メタ情報除去を済ませた画像(元ファイルではない)
     */
    @Async("imageTaggingExecutor")
    public void tagAsync(Long imageId, Long projectId, String mimeType, byte[] imageData) {
        try {
            String raw = aiGenerationClient.generateWithImage(projectId, PROMPT, mimeType, imageData);
            if (raw == null) {
                return;
            }
            List<String> tags = parseTags(raw);
            if (tags.isEmpty()) {
                return;
            }
            GeneratedImage image = generatedImageRepository.findById(imageId).orElse(null);
            // 応答を待つ間に画像が消えた、または利用者が先にタグを付けた場合は何もしない。
            if (image == null || (image.getTagsJson() != null && !image.getTagsJson().isBlank())) {
                return;
            }
            image.setTagsJson(objectMapper.writeValueAsString(tags));
            generatedImageRepository.save(image);
        } catch (Exception e) {
            log.warn("アップロード画像のAIタグ付けに失敗しました(タグなしで登録済みのまま続行します): {}", e.getMessage());
        }
    }

    private List<String> parseTags(String raw) throws Exception {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        String json = start < 0 || end < start ? raw : raw.substring(start, end + 1);
        JsonNode node = objectMapper.readTree(json).get("tags");
        List<String> tags = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(n -> tags.add(n.asText()));
        }
        return tags;
    }
}
