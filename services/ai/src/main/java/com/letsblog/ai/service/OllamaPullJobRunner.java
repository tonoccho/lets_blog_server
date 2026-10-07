package com.letsblog.ai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.letsblog.ai.repository.GenerationJobRepository;
import com.letsblog.common.net.ConnectionDestinationGuard;
import com.letsblog.common.net.GuardedTarget;
import com.letsblog.common.net.PinnedHttpClients;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Ollamaのモデルのpull({@code POST /api/pull}、ストリーミング応答)をバックグラウンドで実行し、進捗・完了・
 * 失敗をGenerationJobへ反映する(issue #1675)。
 *
 * <p>Springの{@code @Async}は同一クラス内の自己呼び出しには効かないため、起動する{@link OllamaPullService}
 * とは別クラスにしている。generation_jobsはこのサービス(ai-service)自身のテーブルなので、
 * 更新はリポジトリへ直接書く。以前media-serviceの非同期ジョブランナーは、起動した要求のBearerトークンを
 * 長時間引き回して失効し、完了通知が失われてジョブがrunningのまま残った(issue #1083)。
 * ここは要求のトークンをそもそも保持せず、HTTP越しの更新も無いので、同じ問題は起きない。
 *
 * <p>どんな失敗も例外にして外へ出さず、ジョブをfailedにして理由を残す。runningのまま残るのは、
 * 最終更新の書き込み自体が失敗したときだけで、その場合は
 * {@link StaleGenerationJobSweepService}がfailedにする。
 */
@Service
public class OllamaPullJobRunner {

    private static final Logger log = LoggerFactory.getLogger(OllamaPullJobRunner.class);
    private static final long PROGRESS_UPDATE_INTERVAL_MS = 500;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    /** 応答の先頭(ヘッダ)が来るまでの上限。ストリーム本体の長さは制限しない(pullは数分〜数十分かかる)。 */
    private static final Duration RESPONSE_START_TIMEOUT = Duration.ofSeconds(60);
    /** ストリームに次の行が来ない時間の上限。超えたら接続を閉じてfailedにし、実行スレッドを解放する。 */
    private static final Duration STREAM_IDLE_TIMEOUT = Duration.ofMinutes(5);
    private static final String RUNNING = "running";

    private final GenerationJobRepository generationJobRepository;
    private final ObjectMapper objectMapper;
    private final ConnectionDestinationGuard destinationGuard;
    private final LongSupplier clockMillis;
    private final Duration idleTimeout;

    @Autowired
    public OllamaPullJobRunner(GenerationJobRepository generationJobRepository, ObjectMapper objectMapper) {
        this(generationJobRepository, objectMapper, ConnectionDestinationGuard.system(), System::currentTimeMillis,
                STREAM_IDLE_TIMEOUT);
    }

    /** テスト専用: 宛先検査と時計を差し替える。 */
    OllamaPullJobRunner(
            GenerationJobRepository generationJobRepository, ObjectMapper objectMapper,
            ConnectionDestinationGuard destinationGuard, LongSupplier clockMillis) {
        this(generationJobRepository, objectMapper, destinationGuard, clockMillis, STREAM_IDLE_TIMEOUT);
    }

    /** テスト専用: 宛先検査・時計・アイドルタイムアウトを差し替える。 */
    OllamaPullJobRunner(
            GenerationJobRepository generationJobRepository, ObjectMapper objectMapper,
            ConnectionDestinationGuard destinationGuard, LongSupplier clockMillis, Duration idleTimeout) {
        this.idleTimeout = idleTimeout;
        this.generationJobRepository = generationJobRepository;
        this.objectMapper = objectMapper;
        this.destinationGuard = destinationGuard;
        this.clockMillis = clockMillis;
    }

    /**
     * @param baseUrl 実効Ollama接続先。OpenAI互換の{@code /v1}で終わっていてもよい(ネイティブAPIの
     *     {@code /api/pull}は{@code /v1}を除いた根に対して呼ぶ)
     * @param projectOverride プロジェクトの上書きなら、接続のたびに宛先を検査する(issue #1547)。
     *     システム設定の接続先は検査しない
     */
    @Async("ollamaPullExecutor")
    public void run(Long jobId, String baseUrl, boolean projectOverride, String model) {
        try {
            pull(jobId, rootUrl(baseUrl), projectOverride, model);
            update(jobId, "done", objectMapper.createObjectNode().put("success", "true"));
        } catch (IOException e) {
            log.warn("Ollama model pull job {} could not reach Ollama", jobId, e);
            fail(jobId, "Ollamaへ接続できませんでした(外部への通信(egress)が無い場合も含みます): " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(jobId, "インストールが中断されました");
        } catch (RuntimeException e) {
            log.warn("Ollama model pull job {} failed", jobId, e);
            fail(jobId, String.valueOf(e.getMessage()));
        }
    }

    private void pull(Long jobId, String root, boolean projectOverride, String model)
            throws IOException, InterruptedException {
        GuardedTarget target = projectOverride
                ? destinationGuard.check("Ollama", root) : new GuardedTarget(root, null);
        HttpClient client = PinnedHttpClients.builder(target.sniHost(), CONNECT_TIMEOUT).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(target.baseUrl() + "/api/pull"))
                .timeout(RESPONSE_START_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.createObjectNode().put("model", model).put("stream", true).toString()))
                .build();
        HttpResponse<Stream<String>> response = client.send(request, HttpResponse.BodyHandlers.ofLines());
        try (Stream<String> lines = response.body()) {
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException(httpErrorMessage(response.statusCode(), lines.collect(Collectors.joining("\n"))));
            }
            readStream(jobId, lines);
        }
    }

    /** NDJSONを1行ずつ読む。error行は失敗、success行で完了。success無しに終わったら失敗。 */
    private void readStream(Long jobId, Stream<String> lines) {
        AtomicLong lastLineAt = new AtomicLong(System.nanoTime());
        AtomicBoolean stalled = new AtomicBoolean();
        ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "ollama-pull-watchdog");
            thread.setDaemon(true);
            return thread;
        });
        long checkMs = Math.max(10, idleTimeout.toMillis() / 4);
        watchdog.scheduleWithFixedDelay(() -> {
            if (System.nanoTime() - lastLineAt.get() >= idleTimeout.toNanos() && stalled.compareAndSet(false, true)) {
                lines.close();
            }
        }, checkMs, checkMs, TimeUnit.MILLISECONDS);
        try {
            readLines(jobId, lines, lastLineAt);
        } catch (RuntimeException e) {
            if (stalled.get()) {
                throw stalledException();
            }
            throw e;
        } finally {
            watchdog.shutdownNow();
        }
        if (stalled.get()) {
            throw stalledException();
        }
    }

    private IllegalStateException stalledException() {
        return new IllegalStateException("Ollamaからの応答が" + idleTimeout.toMinutes()
                + "分以上途絶えたため、インストールを打ち切りました(外部への通信(egress)が途中で切れた可能性があります)");
    }

    private void readLines(Long jobId, Stream<String> lines, AtomicLong lastLineAt) {
        long[] lastReportedAt = {0L};
        boolean[] reported = {false};
        boolean succeeded = false;
        for (String line : (Iterable<String>) lines::iterator) {
            lastLineAt.set(System.nanoTime());
            JsonNode node = parse(line);
            if (node == null) {
                continue;
            }
            if (node.hasNonNull("error")) {
                throw new IllegalStateException("Ollamaがエラーを返しました: " + node.get("error").asText());
            }
            String status = node.path("status").asText("");
            if ("success".equals(status)) {
                succeeded = true;
                continue;
            }
            ObjectNode progress = progressOf(node, status);
            long now = clockMillis.getAsLong();
            if (progress != null && (!reported[0] || now - lastReportedAt[0] >= PROGRESS_UPDATE_INTERVAL_MS)) {
                reported[0] = true;
                lastReportedAt[0] = now;
                update(jobId, RUNNING, progress);
            }
        }
        if (!succeeded) {
            throw new IllegalStateException("Ollamaが完了を知らせる前に応答が終わりました");
        }
    }

    /** 進捗行をジョブの進捗(jobProgress.tsの形)へ。ダウンロード中の行はcompleted/totalから百分率を求める。 */
    private ObjectNode progressOf(JsonNode node, String status) {
        long total = node.path("total").asLong(0);
        if (total > 0 && node.has("completed")) {
            long completed = node.path("completed").asLong(0);
            ObjectNode progress = objectMapper.createObjectNode();
            progress.put("phase", "downloading");
            progress.put("percent", Math.min(100, Math.round(completed * 100.0 / total)));
            progress.put("bytesDone", completed);
            progress.put("bytesTotal", total);
            return progress;
        }
        if (status.isEmpty()) {
            return null;
        }
        ObjectNode progress = objectMapper.createObjectNode();
        progress.put("phase", status);
        progress.putNull("percent");
        progress.putNull("bytesDone");
        progress.putNull("bytesTotal");
        return progress;
    }

    private JsonNode parse(String line) {
        if (line.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(line);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private String httpErrorMessage(int status, String body) {
        JsonNode node = parse(body);
        String reason = node == null ? "" : node.path("error").asText("");
        return "Ollamaがエラーを返しました(HTTP " + status + ")" + (reason.isEmpty() ? "" : ": " + reason);
    }

    private void fail(Long jobId, String reason) {
        update(jobId, "failed", objectMapper.createObjectNode().put("error", reason));
    }

    private void update(Long jobId, String status, ObjectNode payload) {
        generationJobRepository.findById(jobId).ifPresentOrElse(job -> {
            job.setStatus(status);
            job.setResultPayload(payload.toString());
            generationJobRepository.save(job);
        }, () -> log.warn("Ollama model pull job {} not found; dropping status {}", jobId, status));
    }

    /** {@code /v1}(OpenAI互換)と末尾のスラッシュを除いた、Ollamaネイティブ APIの根のURL。 */
    private static String rootUrl(String baseUrl) {
        String root = stripTrailingSlashes(baseUrl.strip());
        if (root.endsWith("/v1")) {
            root = stripTrailingSlashes(root.substring(0, root.length() - "/v1".length()));
        }
        return root;
    }

    private static String stripTrailingSlashes(String url) {
        int end = url.length();
        while (end > 0 && url.charAt(end - 1) == '/') {
            end--;
        }
        return url.substring(0, end);
    }
}
