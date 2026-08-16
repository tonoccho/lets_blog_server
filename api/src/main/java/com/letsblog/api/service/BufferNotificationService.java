package com.letsblog.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.api.buffer.BufferClient;
import com.letsblog.api.buffer.BufferUpdate;
import com.letsblog.api.domain.BufferPost;
import com.letsblog.api.repository.BufferPostRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * 記事公開をBuffer経由でSNSへ予約投稿する(issue #379)。Bufferの/updates/create.jsonは
 * scheduled_atを指定した時点でBuffer側が予約を引き受けるため、公開から実際の投稿までの
 * 遅延自体を当システムが待つ必要はなく、公開時に1回呼び出すだけでよい。
 * SNS通知の失敗で記事公開自体を失敗させないよう、呼び出し元(PostPublishService)から
 * 非同期(@Async)で呼ばれる想定。
 * Buffer連携設定はプロジェクト単位(issue #402)のため、呼び出しの都度ProjectApiKeyServiceから
 * 該当プロジェクトの設定を解決する。
 */
@Service
public class BufferNotificationService {

    private static final Logger log = LoggerFactory.getLogger(BufferNotificationService.class);
    private static final int MAX_ATTEMPTS = 3;

    private final BufferClient bufferClient;
    private final BufferPostRepository bufferPostRepository;
    private final ProjectApiKeyService projectApiKeyService;
    private final ObjectMapper objectMapper;
    private final long retryBackoffMillis;

    public BufferNotificationService(
            BufferClient bufferClient,
            BufferPostRepository bufferPostRepository,
            ProjectApiKeyService projectApiKeyService,
            ObjectMapper objectMapper) {
        this(bufferClient, bufferPostRepository, projectApiKeyService, objectMapper, 2000L);
    }

    /** テスト専用: リトライ間隔を短縮できるようにするコンストラクタ。 */
    BufferNotificationService(
            BufferClient bufferClient,
            BufferPostRepository bufferPostRepository,
            ProjectApiKeyService projectApiKeyService,
            ObjectMapper objectMapper,
            long retryBackoffMillis) {
        this.bufferClient = bufferClient;
        this.bufferPostRepository = bufferPostRepository;
        this.projectApiKeyService = projectApiKeyService;
        this.objectMapper = objectMapper;
        this.retryBackoffMillis = retryBackoffMillis;
    }

    /**
     * postId/siteIdに紐づく記事の公開をBufferへ通知する。プロジェクトでBuffer連携が未設定
     * (有効化されていない/アクセストークンまたはプロファイル未設定)の場合は何もしない。
     * 呼び出し元のトランザクション/HTTPレスポンスをブロックしないよう非同期実行する。
     */
    @Async("bufferNotificationExecutor")
    public void notifyAsync(Long postId, Long siteId, Long projectId, String title, String url) {
        ProjectApiKeyService.BufferSettings settings = projectApiKeyService.resolveBufferSettings(projectId);
        if (!settings.enabled() || settings.profileIds().isEmpty()) {
            return;
        }
        List<String> profileIds = settings.profileIds();

        Instant scheduledAt = Instant.now().plus(settings.delayMinutes(), ChronoUnit.MINUTES);
        String text = buildMessage(settings.messageTemplate(), title, url);

        BufferPost job = new BufferPost();
        job.setPostId(postId);
        job.setSiteId(siteId);
        job.setStatus("pending");
        job.setScheduledAt(LocalDateTime.ofInstant(scheduledAt, ZoneOffset.UTC));
        job.setRequestPayload(toJson(Map.of("profileIds", profileIds, "text", text)));
        bufferPostRepository.save(job);

        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                List<BufferUpdate> updates =
                        bufferClient.createUpdate(profileIds, text, scheduledAt, settings.accessToken());
                job.setStatus("sent");
                job.setResultPayload(toJson(Map.of("bufferUpdateIds", updates.stream().map(BufferUpdate::id).toList())));
                bufferPostRepository.save(job);
                return;
            } catch (RuntimeException e) {
                lastError = e;
                log.warn("Buffer投稿予約に失敗しました(試行{}/{}): postId={}, error={}",
                        attempt, MAX_ATTEMPTS, postId, e.getMessage());
                if (attempt < MAX_ATTEMPTS) {
                    sleep(retryBackoffMillis);
                }
            }
        }

        job.setStatus("failed");
        job.setResultPayload(toJson(Map.of("error", String.valueOf(lastError.getMessage()))));
        bufferPostRepository.save(job);
    }

    private String buildMessage(String messageTemplate, String title, String url) {
        return messageTemplate.replace("{title}", title == null ? "" : title).replace("{url}", url == null ? "" : url);
    }

    private void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
