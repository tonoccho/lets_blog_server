package com.letsblog.media.testsupport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.common.auth.ServiceTokenClient;
import com.letsblog.common.client.ActorProfile;
import com.letsblog.common.client.GenerationJobClient;
import com.letsblog.common.client.IdentityClient;
import com.letsblog.media.ai.ChatGptImageClient;
import com.letsblog.media.ai.ComfyUiClient;
import com.letsblog.media.ai.GeneratedImageSequenceService;
import com.letsblog.media.ai.GeneratedImageStorageService;
import com.letsblog.media.ai.SeedResolver;
import com.letsblog.media.client.AiGenerationClient;
import com.letsblog.media.client.AiServiceConnectionClient;
import com.letsblog.media.client.CmsBridgeClient;
import com.letsblog.media.client.IdentityBridgeClient;
import com.letsblog.media.client.MediaGcScanResult;
import com.letsblog.media.client.OutboundAuthHeaders;
import com.letsblog.media.client.ProjectServiceClient;
import com.letsblog.media.domain.GeneratedImage;
import com.letsblog.media.domain.GeneratedImageSequence;
import com.letsblog.media.domain.ProjectImageSettings;
import com.letsblog.media.messaging.DomainEventPublisher;
import com.letsblog.media.repository.GeneratedImageRepository;
import com.letsblog.media.repository.GeneratedImageSequenceRepository;
import com.letsblog.media.repository.ProjectImageSettingsRepository;
import com.letsblog.media.service.AdminAuthorizationService;
import com.letsblog.media.service.AuditLogService;
import com.letsblog.media.service.ComfyUiModelService;
import com.letsblog.media.service.CurrentActorService;
import com.letsblog.media.service.GeneratedImageCreationService;
import com.letsblog.media.service.GeneratedImageEditService;
import com.letsblog.media.service.GeneratedImageUploadService;
import com.letsblog.media.service.ImageGenerationJobRunner;
import com.letsblog.media.service.ImageGenerationJobTracker;
import com.letsblog.media.service.ImageGenerationService;
import com.letsblog.media.service.ImageResizeService;
import com.letsblog.media.service.UploadedImageTagService;
import com.letsblog.media.service.ImageModelService;
import com.letsblog.media.service.MediaGarbageCollectionJobRunner;
import com.letsblog.media.service.MediaGarbageCollectionService;
import com.letsblog.media.service.ProhibitedContentFilterService;
import com.letsblog.media.service.ProjectImageDefaultsResolver;
import com.letsblog.media.service.ProjectImageSettingsService;
import com.letsblog.media.service.ReferenceImageService;
import com.letsblog.media.service.SafetyNegativePromptService;
import org.mockito.Mockito;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * media-serviceの{@code service}パッケージのテストが使う「外部との通信の境界だけを差し替えた世界」(issue #1819)。
 *
 * <p>{@code *Service}・{@code *Client}・{@code CurrentActorService}・{@code ObjectMapper}・DTOは
 * すべて実物で、外部との通信は次の3つだけを差し替える。
 * <ul>
 *   <li><b>HTTP</b>: {@link FakeHttpServer}(JDK標準のHttpServerをループバックで起動したサーバー側フェイク)。
 *       identity-service・project-service・ai-service・platform-service・publishing-service・ComfyUI・
 *       ChatGPT画像API・トークンエンドポイント(Keycloakの代わり)のベースURLをすべてこの1台へ向ける。</li>
 *   <li><b>MQ</b>: {@link RabbitTemplate}のMockito mock(実物の{@link DomainEventPublisher}・{@link AuditLogService}へ渡す)。</li>
 *   <li><b>DB</b>: Spring Dataのリポジトリ({@link GeneratedImageRepository}・{@link ProjectImageSettingsRepository}・
 *       {@link GeneratedImageSequenceRepository})のMockito mock。保存した行をメモリに持ち、{@code findById}などが
 *       それを返す(本物のテーブルの代わり)。</li>
 * </ul>
 * ファイルの保存は実物の{@link GeneratedImageStorageService}が一時ディレクトリへ実際に書く。
 */
public final class MediaWorld implements AutoCloseable {

    /** MediaWorldが用意するサービストークン。 */
    public static final String SERVICE_TOKEN = "svc-token";
    /** 既定の品質プロンプト・ネガティブプロンプトなど、設定値({@code app.default-*})。 */
    public static final String GLOBAL_NEGATIVE = "global negative";
    public static final String GLOBAL_QUALITY = "global quality";
    public static final String GLOBAL_CHECKPOINT = "global.safetensors";

    public final FakeHttpServer http = new FakeHttpServer();
    public final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    public final MockHttpServletRequest request = new MockHttpServletRequest();
    public final Path storageDir;

    /** DB(Spring Dataのリポジトリ)。外部との通信なので差し替えてよい。 */
    public final GeneratedImageRepository generatedImageRepository = Mockito.mock(GeneratedImageRepository.class);
    public final ProjectImageSettingsRepository projectImageSettingsRepository =
            Mockito.mock(ProjectImageSettingsRepository.class);
    public final GeneratedImageSequenceRepository generatedImageSequenceRepository =
            Mockito.mock(GeneratedImageSequenceRepository.class);
    /** MQ。 */
    public final RabbitTemplate rabbitTemplate = Mockito.mock(RabbitTemplate.class);

    /** DBの代わりのメモリ上の行。 */
    public final Map<Long, GeneratedImage> images = new ConcurrentHashMap<>();
    public final Map<Long, ProjectImageSettings> settings = new ConcurrentHashMap<>();
    /** {@code save}に渡された生成画像を、保存した順に持つ。 */
    public final List<GeneratedImage> savedImages = new CopyOnWriteArrayList<>();
    private final AtomicLong imageIds = new AtomicLong(99);

    public final ServiceTokenClient serviceTokenClient;
    public final IdentityClient identityClient;
    public final CurrentActorService currentActorService;
    public final IdentityBridgeClient identityBridgeClient;
    public final AdminAuthorizationService adminAuthorizationService;
    public final OutboundAuthHeaders outboundAuthHeaders;
    public final AiGenerationClient aiGenerationClient;
    public final GenerationJobClient generationJobClient;
    public final CmsBridgeClient cmsBridgeClient;
    public final ProjectServiceClient projectServiceClient;
    public final AiServiceConnectionClient aiServiceConnectionClient;
    public final ChatGptEndpointPlatformServiceClient platformServiceClient;
    public final ComfyUiClient comfyUiClient;
    public final ChatGptImageClient chatGptImageClient;
    public final DomainEventPublisher domainEventPublisher = new DomainEventPublisher(rabbitTemplate);
    public final AuditLogService auditLogService = new AuditLogService(rabbitTemplate);
    public final GeneratedImageSequenceService sequenceService =
            new GeneratedImageSequenceService(generatedImageSequenceRepository);
    public final GeneratedImageStorageService storageService;
    public final ProjectImageSettingsService projectImageSettingsService =
            new ProjectImageSettingsService(projectImageSettingsRepository);
    public final ImageModelService imageModelService;
    public final ComfyUiModelService comfyUiModelService;
    public final ProjectImageDefaultsResolver defaultsResolver;
    public final ReferenceImageService referenceImageService;
    public final GeneratedImageCreationService creationService;
    public final ProhibitedContentFilterService prohibitedContentFilterService = new ProhibitedContentFilterService();
    public final SafetyNegativePromptService safetyNegativePromptService =
            new SafetyNegativePromptService("nsfw, nude", "gore", "nazi symbol");
    public final ImageGenerationService imageGenerationService;
    public final ImageResizeService imageResizeService = new ImageResizeService();
    public final UploadedImageTagService uploadedImageTagService;
    public final GeneratedImageUploadService uploadService;
    public final GeneratedImageEditService editService;
    public final MediaGarbageCollectionJobRunner gcRunner;
    public final MediaGarbageCollectionService gcService;
    public final ImageGenerationJobTracker tracker;
    public final ImageGenerationJobRunner jobRunner;

    private final Map<String, ActorProfile> profilesByAuthorization = new HashMap<>();
    private final Set<Long> missingProjects = new HashSet<>();
    private final AtomicInteger comfyImagesPerPrompt = new AtomicInteger(1);
    private final AtomicInteger chatGptImagesPerCall = new AtomicInteger(1);
    private final AtomicInteger promptIds = new AtomicInteger();
    private final AtomicLong jobIds = new AtomicLong(9);
    private volatile String comfyUiBaseUrl;

    /** ジョブの作成1回分(ai-serviceの{@code POST generation-jobs}が受けた内容)。 */
    public record JobCreate(String type, JsonNode requestPayload) {
    }

    /** ジョブの更新1回分(ai-serviceの{@code PATCH generation-jobs/{id}}が受けた内容)。 */
    public record JobUpdate(Long jobId, String status, JsonNode payload) {
    }

    public MediaWorld() {
        this(GLOBAL_QUALITY);
    }

    /** 品質プロンプトの設定値({@code app.default-quality-prompt})を指定する(空・nullの場合の分岐用)。 */
    public MediaWorld(String globalQualityPrompt) {
        try {
            storageDir = Files.createTempDirectory("media-world");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        SharedCircuitBreakers.reset();
        String base = http.baseUrl();
        installDefaultRoutes();
        installRepositoryBehavior();

        serviceTokenClient = new ServiceTokenClient(RestClient.builder(), base + "/token", "media-service", "secret");
        identityClient = new IdentityClient(RestClient.builder(), base);
        currentActorService = new CurrentActorService(request, identityClient);
        identityBridgeClient = new IdentityBridgeClient(RestClient.builder(), base);
        adminAuthorizationService = new AdminAuthorizationService(currentActorService, identityBridgeClient);
        outboundAuthHeaders = new OutboundAuthHeaders(request, serviceTokenClient);
        aiGenerationClient = new AiGenerationClient(RestClient.builder(), base, outboundAuthHeaders);
        ObjectProvider<ServiceTokenClient> tokenProvider =
                new StaticListableBeanFactory(Map.of("serviceTokenClient", serviceTokenClient))
                        .getBeanProvider(ServiceTokenClient.class);
        generationJobClient = new GenerationJobClient(RestClient.builder(), base, tokenProvider);
        cmsBridgeClient = new CmsBridgeClient(RestClient.builder(), base, serviceTokenClient);
        projectServiceClient = new ProjectServiceClient(RestClient.builder(), base, outboundAuthHeaders);
        aiServiceConnectionClient = new AiServiceConnectionClient(RestClient.builder(), base, serviceTokenClient);
        platformServiceClient = new ChatGptEndpointPlatformServiceClient(
                base, serviceTokenClient, aiServiceConnectionClient, base);
        comfyUiClient = new ComfyUiClient(platformServiceClient, GLOBAL_CHECKPOINT);
        chatGptImageClient = new ChatGptImageClient(platformServiceClient);
        storageService = new GeneratedImageStorageService(storageDir.toString(), sequenceService);
        imageModelService = new ImageModelService(projectServiceClient, projectImageSettingsService);
        comfyUiModelService = new ComfyUiModelService(
                comfyUiClient, projectServiceClient, projectImageSettingsService, GLOBAL_CHECKPOINT);
        defaultsResolver = new ProjectImageDefaultsResolver(
                projectImageSettingsService, projectServiceClient, GLOBAL_NEGATIVE, globalQualityPrompt,
                1024, 768, 1300, false, false, false);
        referenceImageService = new ReferenceImageService(generatedImageRepository, storageService);
        creationService = new GeneratedImageCreationService(
                generatedImageRepository, storageService, domainEventPublisher, referenceImageService);
        imageGenerationService = new ImageGenerationService(
                aiGenerationClient, comfyUiClient, chatGptImageClient, imageModelService, comfyUiModelService,
                creationService, generationJobClient, objectMapper, defaultsResolver,
                prohibitedContentFilterService, safetyNegativePromptService, new SeedResolver(),
                referenceImageService, request);
        uploadedImageTagService =
                new UploadedImageTagService(aiGenerationClient, generatedImageRepository, objectMapper);
        uploadService = new GeneratedImageUploadService(imageResizeService, creationService, uploadedImageTagService);
        editService = new GeneratedImageEditService(
                storageService, imageResizeService, creationService, generatedImageRepository);
        gcRunner = new MediaGarbageCollectionJobRunner(cmsBridgeClient, generationJobClient, auditLogService, objectMapper);
        gcService = new MediaGarbageCollectionService(cmsBridgeClient, generationJobClient, gcRunner, objectMapper);
        tracker = new ImageGenerationJobTracker(generationJobClient, objectMapper);
        jobRunner = new ImageGenerationJobRunner(imageGenerationService, generationJobClient, objectMapper, tracker);
    }

    // ------------------------------------------------------------------
    // 既定の応答
    // ------------------------------------------------------------------

    private void installDefaultRoutes() {
        http.on("POST", "/token", FakeHttpServer.Response.ok(
                "{\"access_token\":\"" + SERVICE_TOKEN + "\",\"expires_in\":3600}"));
        http.on("GET", "/api/identity/me", req -> {
            ActorProfile profile = profilesByAuthorization.get(req.header("Authorization"));
            return profile == null
                    ? FakeHttpServer.Response.json(401, "{\"error\":\"unauthorized\"}")
                    : FakeHttpServer.Response.ok(toJson(profile));
        });
        // project-service: 既定ではどのプロジェクトも存在する。missingProjectで存在しない状態にする。
        http.on("GET", "/api/internal/project/projects/(\\d+)", req -> {
            long id = Long.parseLong(req.path().substring(req.path().lastIndexOf('/') + 1));
            return missingProjects.contains(id)
                    ? FakeHttpServer.Response.json(404, "{\"error\":\"not found\"}")
                    : FakeHttpServer.Response.ok("{\"id\":" + id + ",\"name\":\"p\",\"slug\":\"p\"}");
        });
        // ai-service: プロジェクトごとの接続先(上書き無し)とAPIキー、LLM、ジョブ。
        http.on("GET", "/api/internal/ai/projects/\\d+/connections",
                FakeHttpServer.Response.ok("{\"ollamaBaseUrl\":null,\"comfyuiBaseUrl\":null}"));
        http.on("GET", "/api/internal/ai/projects/\\d+/openai-api-key",
                FakeHttpServer.Response.ok("{\"apiKey\":\"sk-test\"}"));
        http.on("POST", "/api/internal/ai/generate",
                FakeHttpServer.Response.ok("{\"result\":\"{\\\"tags\\\":[\\\"猫\\\"]}\"}"));
        http.on("POST", "/api/internal/ai/generate-with-image",
                FakeHttpServer.Response.ok("{\"result\":\"{\\\"tags\\\":[\\\"猫\\\"]}\"}"));
        http.on("POST", "/api/internal/ai/generation-jobs", req -> FakeHttpServer.Response.ok(
                "{\"id\":" + jobIds.getAndIncrement() + ",\"type\":\"t\",\"status\":\"running\"}"));
        http.on("PATCH", "/api/internal/ai/generation-jobs/\\d+", FakeHttpServer.Response.empty(204));
        // publishing-service: メディアの削除は既定で成功(204)する。
        http.on("DELETE", "/api/internal/publishing/projects/\\d+/media/.*", FakeHttpServer.Response.empty(204));
        // platform-service: ComfyUIの接続先はこの1台。
        http.on("GET", "/api/internal/platform/image-generation-config",
                req -> FakeHttpServer.Response.ok(toJson(java.util.Collections.singletonMap(
                        "comfyUiBaseUrl", comfyUiBaseUrl != null ? comfyUiBaseUrl : http.baseUrl()))));
        // ComfyUI。
        http.on("POST", "/prompt", req ->
                FakeHttpServer.Response.ok("{\"prompt_id\":\"p" + promptIds.incrementAndGet() + "\"}"));
        http.on("GET", "/history/p\\d+", req -> {
            String promptId = req.path().substring(req.path().lastIndexOf('/') + 1);
            StringBuilder images = new StringBuilder();
            for (int i = 0; i < comfyImagesPerPrompt.get(); i++) {
                if (i > 0) {
                    images.append(',');
                }
                images.append("{\"filename\":\"img").append(i).append(".png\",\"subfolder\":\"\",\"type\":\"output\"}");
            }
            return FakeHttpServer.Response.ok("{\"" + promptId + "\":{\"outputs\":{\"9\":{\"images\":["
                    + images + "]}}}}");
        });
        http.on("GET", "/view", req -> {
            String query = req.query() == null ? "" : req.query();
            int index = query.contains("filename=img") ? query.charAt(query.indexOf("filename=img") + 12) - '0' : 0;
            return FakeHttpServer.Response.bytes(200, "image/png", new byte[] {(byte) index});
        });
        comfyObjectInfo(List.of("a.safetensors"), List.of("euler"), List.of("normal"), List.of());
        http.on("POST", "/upload/image", FakeHttpServer.Response.ok("{\"name\":\"uploaded.png\",\"subfolder\":\"\"}"));
        // ChatGPT画像API。
        http.on("POST", "/images/generations", req -> chatGptResponse());
        http.on("POST", "/images/edits", req -> chatGptResponse());
    }

    private FakeHttpServer.Response chatGptResponse() {
        List<String> items = new ArrayList<>();
        for (int i = 0; i < chatGptImagesPerCall.get(); i++) {
            items.add("{\"b64_json\":\"" + Base64.getEncoder().encodeToString(new byte[] {(byte) i}) + "\"}");
        }
        return FakeHttpServer.Response.ok("{\"data\":[" + String.join(",", items) + "]}");
    }

    @SuppressWarnings("unchecked")
    private void installRepositoryBehavior() {
        when(generatedImageRepository.save(any(GeneratedImage.class))).thenAnswer(inv -> {
            GeneratedImage image = inv.getArgument(0);
            if (image.getId() == null) {
                image.setId(imageIds.incrementAndGet());
            }
            images.put(image.getId(), image);
            savedImages.add(image);
            return image;
        });
        when(generatedImageRepository.findById(any(Long.class)))
                .thenAnswer(inv -> Optional.ofNullable(images.get(inv.getArgument(0))));
        when(projectImageSettingsRepository.findByProjectId(any()))
                .thenAnswer(inv -> Optional.ofNullable(settings.get(inv.getArgument(0))));
        when(projectImageSettingsRepository.save(any(ProjectImageSettings.class))).thenAnswer(inv -> {
            ProjectImageSettings saved = inv.getArgument(0);
            settings.put(saved.getProjectId(), saved);
            return saved;
        });
        Map<String, GeneratedImageSequence> sequences = new ConcurrentHashMap<>();
        doAnswer(inv -> {
            sequences.computeIfAbsent(inv.getArgument(0), GeneratedImageSequence::new);
            return null;
        }).when(generatedImageSequenceRepository).insertIfAbsent(anyString());
        when(generatedImageSequenceRepository.findByProjectKeyForUpdate(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(sequences.get(inv.getArgument(0))));
        when(generatedImageSequenceRepository.save(any(GeneratedImageSequence.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    // ------------------------------------------------------------------
    // 利用者・リクエスト
    // ------------------------------------------------------------------

    /** リクエストの中にいる状態にする({@code RequestContextHolder}へリクエストを束縛する)。 */
    public MediaWorld inRequest() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return this;
    }

    /** 指定の利用者としてリクエストする。{@code Authorization}ヘッダーを付け、identity-serviceがそのプロフィールを返す。 */
    public MediaWorld actingAs(Long userId, String role) {
        String authorization = "Bearer token-" + userId;
        request.removeHeader("Authorization");
        request.addHeader("Authorization", authorization);
        profilesByAuthorization.put(authorization, new ActorProfile(userId, role));
        return inRequest();
    }

    public MediaWorld actingAsAdmin(Long userId) {
        return actingAs(userId, "admin");
    }

    public MediaWorld actingAsUser(Long userId) {
        return actingAs(userId, "user");
    }

    /** JWTのsubを{@code subject}にする(監査用の{@code getCurrentActorKeycloakSub}が返す値)。nullなら未ログイン。 */
    public MediaWorld signedInAs(String subject) {
        SecurityContextHolder.clearContext();
        if (subject != null) {
            Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject(subject).build();
            SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        }
        return this;
    }

    /** identity-serviceが{@code /api/identity/me}で障害(5xx)を返す状態にする。 */
    public MediaWorld identityServiceDown() {
        http.status("GET", "/api/identity/me", 503, "{\"error\":\"down\"}");
        return this;
    }

    // ------------------------------------------------------------------
    // 状態の設定
    // ------------------------------------------------------------------

    /** project-serviceに存在しないプロジェクトにする。 */
    public MediaWorld projectMissing(long projectId) {
        missingProjects.add(projectId);
        return this;
    }

    /** プロジェクト設定(DBの{@code project_image_settings}の行)を用意する。 */
    public ProjectImageSettings settingsFor(long projectId) {
        return settings.computeIfAbsent(projectId, id -> {
            ProjectImageSettings s = new ProjectImageSettings(id);
            return s;
        });
    }

    /** DBにある生成画像の行を用意し、そのファイルを保存先へ書く。 */
    public GeneratedImage existingImage(long id, Long projectId, byte[] data, String mimeType) {
        GeneratedImage image = new GeneratedImage();
        image.setId(id);
        image.setProjectId(projectId);
        image.setMimeType(mimeType);
        image.setFilePath(storageService.store(projectId, data));
        images.put(id, image);
        return image;
    }

    /** publishing-serviceのメディアスキャン({@code GET .../media-scan})が返す内容。 */
    public MediaWorld publishingScan(MediaGcScanResult result) {
        http.json("GET", "/api/internal/publishing/projects/\\d+/media-scan", toJson(result));
        return this;
    }

    /** ComfyUIの{@code /object_info/*}が返す、チェックポイント・サンプラー・スケジューラー・LoRAの一覧。 */
    public MediaWorld comfyObjectInfo(
            List<String> checkpoints, List<String> samplers, List<String> schedulers, List<String> loras) {
        http.json("GET", "/object_info/CheckpointLoaderSimple", toJson(Map.of("CheckpointLoaderSimple",
                Map.of("input", Map.of("required", Map.of("ckpt_name", List.of(checkpoints)))))));
        http.json("GET", "/object_info/KSampler", toJson(Map.of("KSampler", Map.of("input", Map.of("required",
                Map.of("sampler_name", List.of(samplers), "scheduler", List.of(schedulers)))))));
        http.json("GET", "/object_info/LoraLoader", toJson(Map.of("LoraLoader",
                Map.of("input", Map.of("required", Map.of("lora_name", List.of(loras)))))));
        return this;
    }

    /** platform-serviceが返すComfyUIの接続先を差し替える(到達できない接続先・未設定(空)の再現用)。 */
    public MediaWorld comfyUiBaseUrlIs(String baseUrl) {
        this.comfyUiBaseUrl = baseUrl;
        return this;
    }

    /** ComfyUIが1回の投入で返す画像の枚数。 */
    public MediaWorld comfyReturns(int count) {
        comfyImagesPerPrompt.set(count);
        return this;
    }

    /** ChatGPT画像APIが1回の呼び出しで返す画像の枚数。 */
    public MediaWorld chatGptReturns(int count) {
        chatGptImagesPerCall.set(count);
        return this;
    }

    /** ai-serviceのLLM({@code POST /api/internal/ai/generate})が返す文字列。 */
    public MediaWorld llmReturns(String result) {
        http.on("POST", "/api/internal/ai/generate", FakeHttpServer.Response.ok(toJson(Map.of("result", result))));
        return this;
    }

    /** プロジェクトの画像生成プロバイダを設定する({@code COMFYUI} / {@code CHATGPT})。 */
    public MediaWorld providerIs(long projectId, String provider) {
        settingsFor(projectId).setImageProvider(provider);
        return this;
    }

    // ------------------------------------------------------------------
    // 観測
    // ------------------------------------------------------------------

    /** ComfyUIへ投入されたワークフローを、投入された順に返す。 */
    public List<JsonNode> comfyWorkflows() {
        return http.requests("POST", "/prompt").stream().map(r -> bodyOf(r).get("prompt")).toList();
    }

    public JsonNode lastComfyWorkflow() {
        List<JsonNode> workflows = comfyWorkflows();
        return workflows.get(workflows.size() - 1);
    }

    /** ComfyUIへ投入されたワークフローから読み取った生成パラメータ。 */
    public record ComfyParams(
            String prompt, String negativePrompt, int steps, double cfg, String sampler, String scheduler, long seed,
            Integer width, Integer height, Integer batchSize, String checkpoint, String lora, Double loraWeight,
            double denoise, boolean img2img) {
    }

    public ComfyParams comfyParams(JsonNode workflow) {
        JsonNode sampler = workflow.path("3").path("inputs");
        JsonNode latent = workflow.path("5").path("inputs");
        JsonNode lora = workflow.path("10").path("inputs");
        return new ComfyParams(
                workflow.path("6").path("inputs").path("text").asText(),
                workflow.path("7").path("inputs").path("text").asText(),
                sampler.path("steps").asInt(), sampler.path("cfg").asDouble(),
                sampler.path("sampler_name").asText(), sampler.path("scheduler").asText(),
                sampler.path("seed").asLong(),
                latent.has("width") ? latent.get("width").asInt() : null,
                latent.has("height") ? latent.get("height").asInt() : null,
                latent.has("batch_size") ? latent.get("batch_size").asInt() : null,
                workflow.path("4").path("inputs").path("ckpt_name").asText(),
                lora.has("lora_name") ? lora.get("lora_name").asText() : null,
                lora.has("strength_model") ? lora.get("strength_model").asDouble() : null,
                sampler.path("denoise").asDouble(), workflow.has("11"));
    }

    /** 投入されたワークフローの生成パラメータを、投入された順に返す。 */
    public List<ComfyParams> allComfyParams() {
        return comfyWorkflows().stream().map(this::comfyParams).toList();
    }

    public ComfyParams lastComfyParams() {
        return comfyParams(lastComfyWorkflow());
    }

    /** ai-serviceが受けたジョブ作成。 */
    public List<JobCreate> jobCreates() {
        return http.requests("POST", "/api/internal/ai/generation-jobs").stream().map(r -> {
            JsonNode body = bodyOf(r);
            return new JobCreate(body.path("type").asText(), parse(body.path("requestPayload").asText()));
        }).toList();
    }

    /** ai-serviceが受けたジョブ更新(受けた順)。 */
    public List<JobUpdate> jobUpdates() {
        return http.requests("PATCH", "/api/internal/ai/generation-jobs/\\d+").stream().map(r -> {
            JsonNode body = bodyOf(r);
            Long jobId = Long.parseLong(r.path().substring(r.path().lastIndexOf('/') + 1));
            return new JobUpdate(jobId, body.path("status").asText(), parse(body.path("resultPayload").asText()));
        }).toList();
    }

    /** 指定の状態へのジョブ更新だけを返す。 */
    public List<JobUpdate> jobUpdates(String status) {
        return jobUpdates().stream().filter(u -> u.status().equals(status)).toList();
    }

    /** MQへ送られたメッセージ({@code convertAndSend}の第3引数)。 */
    public List<Object> publishedMessages() {
        List<Object> messages = new ArrayList<>();
        Mockito.mockingDetails(rabbitTemplate).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("convertAndSend") && i.getArguments().length == 3)
                .forEach(i -> messages.add(i.getArguments()[2]));
        return messages;
    }

    /** 保存先に書かれたファイルの数。 */
    public long storedFileCount() {
        try (Stream<Path> files = Files.walk(storageDir)) {
            return files.filter(Files::isRegularFile).count();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public JsonNode bodyOf(FakeHttpServer.Request request) {
        try {
            return objectMapper.readTree(request.body());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (IOException e) {
            throw new IllegalStateException("JSONではありません: " + json, e);
        }
    }

    @Override
    public void close() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
        http.close();
        try (Stream<Path> files = Files.walk(storageDir)) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            // 一時ディレクトリの後始末の失敗はテストの結果に関係しない。
        }
    }

}
