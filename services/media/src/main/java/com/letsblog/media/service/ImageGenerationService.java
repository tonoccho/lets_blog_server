package com.letsblog.media.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.media.ai.ChatGptImageClient;
import com.letsblog.media.ai.ComfyUiClient;
import com.letsblog.media.ai.ComfyUiGenerationParams;
import com.letsblog.media.ai.ComfyUiImage;
import com.letsblog.media.ai.ImageGenerationProvider;
import com.letsblog.media.ai.ImageProvider;
import com.letsblog.media.ai.SeedResolver;
import com.letsblog.media.client.AiGenerationClient;
import com.letsblog.media.client.GenerationJobClient;
import com.letsblog.media.dto.AiImageBatchResponse;
import com.letsblog.media.dto.AiImageRequest;
import com.letsblog.media.dto.AiImageResponse;
import com.letsblog.media.dto.CreateGeneratedImageRequest;
import com.letsblog.media.dto.ImageGenerationOptionsResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

/**
 * ComfyUI/ChatGPTによる画像生成。呼び出しごとに{@code generation_jobs}
 * (ai-serviceが所有、issue #574)へ履歴を記録する。
 *
 * <p>issue #583でlegacy-apiの{@code AiAssistService}から移設した。#573・#574の時点では
 * 「生成画像の保存責務だけをmedia-serviceへ委譲し、生成AI呼び出し自体はlegacy-apiに残す」という
 * 判断だったが、#583のlegacy-api解体にあたり<b>画像生成一式をmedia-serviceへ寄せる</b>と決めた
 * (media-serviceは既に{@code generated_images}・ComfyUIチェックポイントの実体・
 * インストールワーカーを所有しており、生成だけが別サービスに残っている状態のほうが不自然なため)。
 *
 * <p>移設により、生成した画像の保存がHTTP({@code POST /api/generated-images})から
 * 同一サービス内の直接呼び出し({@link GeneratedImageCreationService})になった。
 *
 * <p>LLM呼び出し(生成画像のタグ提案)だけは所有権がai-service(#574)にあるため、
 * {@link AiGenerationClient}経由で委譲する。画像生成プロンプトの組み立て
 * ({@code POST /api/projects/{id}/ai/generate-image-prompt})は純粋なLLM機能なので
 * #583でai-serviceへ移した(こちらには無い)。
 */
@Service
public class ImageGenerationService {

    private static final Logger log = LoggerFactory.getLogger(ImageGenerationService.class);

    /**
     * 何回続けて失敗したら残りのリピートを打ち切るか(issue #1102 レビュー指摘)。
     *
     * <p>認証エラー・設定不備・チェックポイント不在のような決定的な原因は、リピートを
     * 重ねても同じように失敗する。{@code /prompt}投入後に落ちる経路では1リピートごとに
     * {@link com.letsblog.media.ai.ComfyUiClient}のポーリング予算を使い切るため、
     * 打ち切りが無いと一つの設定ミスが1リクエストを最大
     * {@code batchCountの上限16 × 188秒 ≒ 50分}掴んだあげく、1リピート目で既に
     * 判明していたエラーを返すことになる。
     *
     * <p>2にしてあるのは、1だと一過性の失敗(VRAMの一時不足など)で残りを捨ててしまい、
     * 「途中で失敗しても成功分は返す」(Requirement 10)の意図と衝突するため。
     * 2回続けて落ちたなら、原因は個別のリピートではなく環境側にあると判断する。
     */
    private static final int MAX_CONSECUTIVE_FAILURES = 2;

    /** issue #281: 生成画像の検索・分類用タグを、画像生成に使ったプロンプトから提案させる。 */
    private static final String IMAGE_TAGS_PROMPT_TEMPLATE = """
            以下は画像生成AIに渡したプロンプトです。この画像を検索・分類しやすくするための
            短い日本語タグを3〜5個程度提案してください。
            出力は必ず次のJSON形式のみとし、他の文章は一切含めないでください。

            {"tags": ["タグ1", "タグ2", "タグ3"]}

            画像生成プロンプト:
            %s
            """;

    private final AiGenerationClient aiGenerationClient;
    private final ComfyUiClient comfyUiClient;
    private final ChatGptImageClient chatGptImageClient;
    private final ImageModelService imageModelService;
    private final ComfyUiModelService comfyUiModelService;
    private final GeneratedImageCreationService generatedImageCreationService;
    private final GenerationJobClient generationJobClient;
    private final ObjectMapper objectMapper;
    private final ProjectImageDefaultsResolver defaultsResolver;
    private final ProhibitedContentFilterService prohibitedContentFilterService;
    private final SafetyNegativePromptService safetyNegativePromptService;
    private final SeedResolver seedResolver;
    private final HttpServletRequest request;

    public ImageGenerationService(
            AiGenerationClient aiGenerationClient,
            ComfyUiClient comfyUiClient,
            ChatGptImageClient chatGptImageClient,
            ImageModelService imageModelService,
            ComfyUiModelService comfyUiModelService,
            GeneratedImageCreationService generatedImageCreationService,
            GenerationJobClient generationJobClient,
            ObjectMapper objectMapper,
            ProjectImageDefaultsResolver defaultsResolver,
            ProhibitedContentFilterService prohibitedContentFilterService,
            SafetyNegativePromptService safetyNegativePromptService,
            SeedResolver seedResolver,
            HttpServletRequest request) {
        this.aiGenerationClient = aiGenerationClient;
        this.comfyUiClient = comfyUiClient;
        this.chatGptImageClient = chatGptImageClient;
        this.imageModelService = imageModelService;
        this.comfyUiModelService = comfyUiModelService;
        this.generatedImageCreationService = generatedImageCreationService;
        this.generationJobClient = generationJobClient;
        this.objectMapper = objectMapper;
        this.defaultsResolver = defaultsResolver;
        this.prohibitedContentFilterService = prohibitedContentFilterService;
        this.safetyNegativePromptService = safetyNegativePromptService;
        this.seedResolver = seedResolver;
        this.request = request;
    }

    /**
     * 1リクエストで{@code batchSize × batchCount}枚を生成する(issue #1102)。
     *
     * <p>リピートは<b>この中で</b>回す。クライアントに{@code batchCount}回呼ばせないのは、
     * {@code /api/ai/image}がgatewayのupload-endpoint枠(プロセス全体で1時間に10回)に
     * 属し、リピートのたびに枠を消費すると同じ1時間に走る他の生成・アップロードを
     * 巻き添えで429にするため。
     *
     * <p>{@code generation_jobs}のジョブは1リクエストにつき1件のままにする
     * (リピートごとに作ると履歴が荒れる)。結果ペイロードには総枚数に加えて
     * 成功・失敗したリピート数を残す。
     *
     * <p>途中のリピートが失敗しても成功分は返すが、{@link #MAX_CONSECUTIVE_FAILURES}回
     * 続けて失敗したら残りのリピートは<b>打ち切る</b>。決定的な原因で毎回同じように
     * 失敗するのにリピート回数ぶん試し続けると、1リクエストを何十分も掴んだあげく
     * 1回目で判明していたエラーを返すことになるため。
     */
    public AiImageBatchResponse generateImage(AiImageRequest imageRequest) {
        ImageProvider provider = imageModelService.getSelectedProvider(imageRequest.projectId());
        int batchSize = imageRequest.batchSize() != null ? imageRequest.batchSize() : 1;
        // ジョブを作る前に判定する。プロバイダが受け付けない枚数は生成が1枚も始まらないので、
        // 履歴に「失敗したジョブ」を残す意味が無い(受入基準「生成は開始されない」)。
        requireBatchSizeWithinProviderLimit(provider, batchSize);
        Long jobId = startJob(
                provider == ImageProvider.CHATGPT ? "chatgpt_image" : "comfyui_image",
                Map.of("prompt", imageRequest.prompt()));
        try {
            BatchOutcome outcome = runBatch(provider, imageRequest, true, (done, total) -> { });
            completeJob(jobId, jobResult(
                    outcome.images().size(), outcome.succeededRepeats(), outcome.failedRepeats(),
                    outcome.attemptedRepeats(), outcome.aborted()));
            return new AiImageBatchResponse(outcome.images(), outcome.failedRepeats());
        } catch (RuntimeException e) {
            failJob(jobId, e);
            throw e;
        }
    }

    /**
     * 非同期ジョブ(issue #1405)が受理前に呼ぶ検証。プロバイダが受け付けない枚数を、
     * ジョブを作る前に{@link UnsupportedBatchSizeException}(400)で断る。
     *
     * @return 生成に使われるプロバイダ
     */
    public ImageProvider requireAcceptable(AiImageRequest imageRequest) {
        ImageProvider provider = imageModelService.getSelectedProvider(imageRequest.projectId());
        requireBatchSizeWithinProviderLimit(
                provider, imageRequest.batchSize() != null ? imageRequest.batchSize() : 1);
        return provider;
    }

    /**
     * ジョブ管理を含まない生成本体(issue #1405)。非同期ジョブランナーが、受理時に作られた
     * ジョブの中で呼ぶ。{@link #generateImage}と違い自前ではジョブを作らず、
     * {@code includeImageData}がfalseなら画像のBase64を保持しない(#1112: 生成画像は
     * {@code generated_images}に永続化済みで、ジョブへはIDだけを残せばよい)。
     */
    public BatchOutcome generateBatch(
            AiImageRequest imageRequest, boolean includeImageData, RepeatProgressListener listener) {
        return runBatch(
                imageModelService.getSelectedProvider(imageRequest.projectId()), imageRequest, includeImageData,
                listener);
    }

    /** リピートが1つ終わる(成功・失敗を問わない)たびに呼ばれる。 */
    @FunctionalInterface
    public interface RepeatProgressListener {
        void repeatFinished(int completedRepeats, int totalRepeats);
    }

    /** {@link #generateBatch}の結果。 */
    public record BatchOutcome(
            List<AiImageResponse> images, int succeededRepeats, int failedRepeats, int attemptedRepeats,
            boolean aborted) {
    }

    private BatchOutcome runBatch(
            ImageProvider provider, AiImageRequest imageRequest, boolean includeImageData,
            RepeatProgressListener listener) {
        ImageGenerationProvider generator = provider == ImageProvider.CHATGPT ? chatGptImageClient : comfyUiClient;
        int batchCount = imageRequest.batchCount() != null ? imageRequest.batchCount() : 1;
        // 禁止コンテンツの検査とタグ提案はプロンプト単位なので、リピートの外で1回だけ行う。
        // プロンプトはリピート間で変わらない(変わるのはseedだけ)。
        // 既定値の解決はDB往復を伴うので、リピート間で変わらないものは1回だけ引く
        // (issue #1102 レビュー指摘。batchCount=16のとき品質プロンプトを18回引いていた)。
        String prompt = resolvePrompt(imageRequest);
        // issue #1085: ブロックフラグはこのあとnegative promptの安全側抑制語連結にも使うため、
        // resolveParamsの中で再度引き直さず、ここで1回だけ解決して使い回す
        // (issue #1102 レビュー指摘と同じ「リピート間で変わらない値は1回だけ引く」方針)。
        boolean blockSexual = defaultsResolver.resolveBlockSexualContent(imageRequest.projectId());
        boolean blockViolent = defaultsResolver.resolveBlockViolentContent(imageRequest.projectId());
        boolean blockDiscriminatory = defaultsResolver.resolveBlockDiscriminatoryContent(imageRequest.projectId());
        prohibitedContentFilterService.check(prompt, blockSexual, blockViolent, blockDiscriminatory);
        String tagsJson = suggestImageTagsJson(prompt);
        ComfyUiGenerationParams baseParams = resolveParams(
                imageRequest, prompt, provider, blockSexual, blockViolent, blockDiscriminatory);
        List<AiImageResponse> responses = new ArrayList<>();
        RuntimeException firstFailure = null;
        int consecutiveFailures = 0;
        int attemptedRepeats = 0;
        int succeededRepeats = 0;
        for (int repeat = 0; repeat < batchCount; repeat++) {
            ComfyUiGenerationParams params = withRepeatSeed(baseParams, imageRequest, provider, repeat);
            attemptedRepeats++;
            try {
                responses.addAll(generateRepeat(
                        generator, params, imageRequest, provider, tagsJson, includeImageData));
                succeededRepeats++;
                consecutiveFailures = 0;
            } catch (RuntimeException e) {
                // 途中のリピートが落ちても、それまでに成功した画像は捨てない(issue #1102)。
                // 200枚生成したあとの1回の失敗で全部を失うほうが損失が大きい。
                consecutiveFailures++;
                if (firstFailure == null) {
                    firstFailure = e;
                } else {
                    // 2件目以降の失敗を捨てない。投げるのは最初の失敗なので、
                    // 後続の失敗をそれに添えてログ・スタックトレースに残す。
                    addSuppressedIfDistinct(firstFailure, e);
                }
                log.warn("画像生成のリピート{}/{}に失敗しました(成功分は返します): {}",
                        repeat + 1, batchCount, e.getMessage());
                listener.repeatFinished(attemptedRepeats, batchCount);
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    // 認証エラー・設定不備・チェックポイント不在のような決定的な原因は、
                    // 何度繰り返しても同じように失敗する。/prompt投入後に落ちる経路では
                    // 1リピートごとにフルのポーリング予算を使い切るため、打ち切らないと
                    // 一つの設定ミスがリクエストを何十分も掴んだあげく、1回目で既に
                    // 判明していたエラーを返すことになる(issue #1102 レビュー指摘)。
                    log.warn("画像生成が{}回続けて失敗したため、残り{}リピートを打ち切ります",
                            consecutiveFailures, batchCount - attemptedRepeats);
                    break;
                }
                continue;
            }
            // 通知の失敗をリピートの失敗として数えないよう、tryの外で呼ぶ。
            listener.repeatFinished(attemptedRepeats, batchCount);
        }
        if (responses.isEmpty() && firstFailure != null) {
            // 1枚も作れなかった場合は「成功した部分」が無いので、従来どおり失敗として返す。
            // プロバイダが例外を投げずに0枚を返した場合(firstFailureがnull)は失敗ではないので、
            // #1102以前と同じく空の結果をそのまま返す。
            throw firstFailure;
        }
        // 打ち切って一度も試さなかったリピートも「成功しなかった」ので失敗として数える。
        // こうすると 成功リピート数 + 失敗リピート数 が常に要求したリピート回数に一致し、
        // 「256枚頼んで16枚しか無い」理由を数えるだけで追える。実際に何回試したのかは
        // attemptedRepeats に残す。
        int failedRepeats = batchCount - succeededRepeats;
        return new BatchOutcome(
                responses, succeededRepeats, failedRepeats, attemptedRepeats, attemptedRepeats < batchCount);
    }

    /** 1リピート分を生成し、保存してレスポンスへ組み立てる。 */
    private List<AiImageResponse> generateRepeat(
            ImageGenerationProvider generator,
            ComfyUiGenerationParams params,
            AiImageRequest imageRequest,
            ImageProvider provider,
            String tagsJson,
            boolean includeImageData) {
        List<ComfyUiImage> images = generator.generateImage(params);
        List<AiImageResponse> responses = new ArrayList<>();
        // issue #1101: バッチ内の位置(0起点)を1枚ずつ振り、seedとともに行とレスポンスへ残す。
        // これが無いと、batchSize>1で生成した複数枚が全て同じ行内容になり区別できない。
        // issue #1102: リピートごとに0から振り直す。同一リピート内はseedが同じで、
        // 区別するのはbatchIndex。リピートが変わればseedが変わる。
        int batchIndex = 0;
        for (ComfyUiImage image : images) {
            Long savedId = generatedImageCreationService.create(new CreateGeneratedImageRequest(
                    imageRequest.projectId(), params.prompt(), params.negativePrompt(), params.steps(),
                    params.cfgScale(), params.samplerName(), params.scheduler(), params.seed(),
                    params.width(), params.height(), params.batchSize(), batchIndex, params.checkpoint(),
                    params.loraName(), params.loraWeight(), image.mimeType(), provider.name(), tagsJson,
                    image.data())).getId();
            String base64 = includeImageData ? Base64.getEncoder().encodeToString(image.data()) : null;
            responses.add(new AiImageResponse(
                    savedId, image.fileName(), base64, image.mimeType(), params.seed(), batchIndex));
            batchIndex++;
        }
        return responses;
    }

    /**
     * プロバイダごとの{@code batchSize}上限を判定する(issue #1102)。
     * {@code AiImageRequest}の{@code @Max(16)}は全プロバイダ共通の上限で、
     * 実効上限はプロバイダによって下がる(CHATGPT=10)。プロバイダはプロジェクト設定から
     * 実行時に決まるためBean Validationでは表現できない。
     */
    private void requireBatchSizeWithinProviderLimit(ImageProvider provider, int batchSize) {
        if (batchSize > provider.maxBatchSize()) {
            throw new UnsupportedBatchSizeException(
                    "画像生成AI " + provider.name() + " が1回に生成できる枚数の上限は "
                            + provider.maxBatchSize() + " 枚です(指定値: " + batchSize + " 枚)。");
        }
    }

    /**
     * generation_jobsへ残す結果。順序を固定するためLinkedHashMapを使う。
     *
     * <p>{@code failedRepeats}には打ち切って一度も試さなかったリピートも含むため、
     * それだけでは「16回試して全部落ちた」のか「2回で打ち切った」のか区別できない。
     * 実際にプロバイダを呼んだ回数{@code attemptedRepeats}と打ち切りの有無
     * {@code aborted}を併せて残す(issue #1102 レビュー指摘)。
     */
    private Map<String, String> jobResult(
            int count, int succeededRepeats, int failedRepeats, int attemptedRepeats, boolean aborted) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("count", String.valueOf(count));
        result.put("succeededRepeats", String.valueOf(succeededRepeats));
        result.put("failedRepeats", String.valueOf(failedRepeats));
        result.put("attemptedRepeats", String.valueOf(attemptedRepeats));
        result.put("aborted", String.valueOf(aborted));
        return result;
    }

    /**
     * 同じ例外インスタンスを自分自身に添えると{@link IllegalArgumentException}になるため、
     * 別インスタンスのときだけ添える。
     */
    private static void addSuppressedIfDistinct(RuntimeException target, RuntimeException candidate) {
        if (target != candidate) {
            target.addSuppressed(candidate);
        }
    }

    public ImageGenerationOptionsResponse getImageOptions(Long projectId) {
        return new ImageGenerationOptionsResponse(
                comfyUiClient.listCheckpoints(),
                comfyUiModelService.getSelectedCheckpointOrGlobalDefault(projectId),
                comfyUiClient.listSamplers(),
                comfyUiClient.listSchedulers(),
                comfyUiClient.listLoras(),
                defaultsResolver.resolveDefaultGeneratedImageWidth(projectId),
                defaultsResolver.resolveDefaultGeneratedImageHeight(projectId),
                defaultsResolver.resolveDefaultNegativePrompt(projectId),
                defaultsResolver.resolveDefaultQualityPrompt(projectId));
    }

    /**
     * 画像生成プロンプトから検索・分類用のタグを提案し、JSON配列文字列として返す(issue #281)。
     * タグ提案はあくまで補助機能のため、LLM呼び出しの失敗で画像生成自体を失敗させない
     * (取得できない場合はタグなし=nullを返す)。
     */
    private String suggestImageTagsJson(String prompt) {
        try {
            String raw = aiGenerationClient.generate(null, IMAGE_TAGS_PROMPT_TEMPLATE.formatted(prompt), null);
            JsonNode node = objectMapper.readTree(extractJsonObject(raw));
            List<String> tags = toStringList(node.get("tags"));
            if (tags.isEmpty()) {
                return null;
            }
            return objectMapper.writeValueAsString(tags);
        } catch (Exception e) {
            log.warn("生成画像のタグ提案に失敗しました(タグなしで保存を続行します): {}", e.getMessage());
            return null;
        }
    }

    /**
     * リクエストの未指定項目をプロジェクト既定値・サーバー既定値で埋める。
     *
     * <p><b>1リクエストにつき1回だけ呼ぶ</b>(issue #1102 レビュー指摘)。既定値の解決は
     * {@code project_image_settings}などへのDB往復を伴い、値はリクエスト内で変わらない。
     * リピートごとに呼んでいた実装では、{@code batchCount=16}で品質プロンプトを18回
     * 引いていた。リピートごとに変わるのはseedだけなので、それは
     * {@link #withRepeatSeed}が差し替える。
     *
     * <p>seedはここでは決めない(nullのまま)。
     *
     * <p>issue #1085: 有効な安全側ブロックカテゴリの抑制語をnegative promptへ連結する。
     * <b>COMFYUIのときだけ</b>連結する — ChatGPTはnegative promptを送れず、連結した語を
     * {@code generated_images.negative_prompt}に残すと「使っていない語」を記録してしまう
     * (Requirement 6)。連結はユーザー指定のnegativePromptを解決した<b>あと</b>に行うため、
     * ユーザー指定があっても消えない。ブロック判定({@link ProhibitedContentFilterService#check})は
     * この連結より前にpositive prompt(引数{@code prompt})に対して既に完了しているため、
     * 連結した語自身がブロック判定に巻き込まれることもない。
     */
    private ComfyUiGenerationParams resolveParams(
            AiImageRequest imageRequest,
            String prompt,
            ImageProvider provider,
            boolean blockSexual,
            boolean blockViolent,
            boolean blockDiscriminatory) {
        String checkpoint = imageRequest.checkpoint() != null && !imageRequest.checkpoint().isBlank()
                ? imageRequest.checkpoint()
                : comfyUiModelService.getSelectedCheckpointOrGlobalDefault(imageRequest.projectId());
        String negativePrompt = imageRequest.negativePrompt() != null && !imageRequest.negativePrompt().isBlank()
                ? imageRequest.negativePrompt()
                : defaultsResolver.resolveDefaultNegativePrompt(imageRequest.projectId());
        if (provider == ImageProvider.COMFYUI) {
            negativePrompt = safetyNegativePromptService.appendSafetyWords(
                    negativePrompt, blockSexual, blockViolent, blockDiscriminatory);
        }
        return new ComfyUiGenerationParams(
                prompt,
                negativePrompt,
                imageRequest.steps() != null ? imageRequest.steps() : 20,
                imageRequest.cfgScale() != null ? imageRequest.cfgScale() : 7.0,
                imageRequest.samplerName() != null ? imageRequest.samplerName() : "euler",
                imageRequest.scheduler() != null ? imageRequest.scheduler() : "normal",
                null,
                imageRequest.width() != null
                        ? imageRequest.width()
                        : defaultsResolver.resolveDefaultGeneratedImageWidth(imageRequest.projectId()),
                imageRequest.height() != null
                        ? imageRequest.height()
                        : defaultsResolver.resolveDefaultGeneratedImageHeight(imageRequest.projectId()),
                imageRequest.batchSize() != null ? imageRequest.batchSize() : 1,
                checkpoint,
                imageRequest.loraName(),
                imageRequest.loraWeight()
        );
    }

    /**
     * リピート{@code repeatIndex}回目(0起点)で使うseedを{@code baseParams}へ差し込む。
     *
     * <p>issue #1101: seedの実値はここで決まる。COMFYUIプロバイダへ渡す
     * {@link ComfyUiGenerationParams#seed()}は常に非nullになり、その値がそのまま
     * {@code generated_images.seed}とAPIレスポンスに載る。CHATGPTは
     * {@code ChatGptImageClient}がseedを無視する(gpt-image-1がseedを受け付けない)ため、
     * 再現できないことが分かるようnullのままにする。
     *
     * <p>issue #1102: リピート間で変わるのはseedだけ。他の項目は
     * {@link #resolveParams}が1回だけ解決した値をそのまま使い回す。
     */
    private ComfyUiGenerationParams withRepeatSeed(
            ComfyUiGenerationParams baseParams,
            AiImageRequest imageRequest,
            ImageProvider provider,
            int repeatIndex) {
        Long seed = provider == ImageProvider.CHATGPT
                ? null
                : seedResolver.resolve(imageRequest.seed(), repeatIndex);
        return new ComfyUiGenerationParams(
                baseParams.prompt(),
                baseParams.negativePrompt(),
                baseParams.steps(),
                baseParams.cfgScale(),
                baseParams.samplerName(),
                baseParams.scheduler(),
                seed,
                baseParams.width(),
                baseParams.height(),
                baseParams.batchSize(),
                baseParams.checkpoint(),
                baseParams.loraName(),
                baseParams.loraWeight());
    }

    /**
     * プロジェクト既定の品質プロンプトを足した、実際に生成へ渡すプロンプト。
     * リピートをまたいで変わらないので、1リクエストにつき1回だけ引き、禁止コンテンツ検査・
     * タグ提案・パラメータ組み立てで使い回す(issue #1102 レビュー指摘)。
     */
    private String resolvePrompt(AiImageRequest imageRequest) {
        String qualityPrompt = defaultsResolver.resolveDefaultQualityPrompt(imageRequest.projectId());
        return qualityPrompt == null || qualityPrompt.isBlank()
                ? imageRequest.prompt()
                : imageRequest.prompt() + ", " + qualityPrompt;
    }

    private String extractJsonObject(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end < 0 || end < start) {
            return raw;
        }
        return raw.substring(start, end + 1);
    }

    private List<String> toStringList(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(n -> result.add(n.asText()));
        }
        return result;
    }

    private String bearerToken() {
        return request.getHeader(HttpHeaders.AUTHORIZATION);
    }

    private Long startJob(String type, Map<String, String> requestPayload) {
        return generationJobClient.create(type, toJson(requestPayload), bearerToken()).id();
    }

    private void completeJob(Long jobId, Map<String, String> resultPayload) {
        generationJobClient.updateStatus(jobId, "done", toJson(resultPayload));
    }

    private void failJob(Long jobId, Exception e) {
        generationJobClient.updateStatus(
                jobId, "failed", toJson(Map.of("error", String.valueOf(e.getMessage()))));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
