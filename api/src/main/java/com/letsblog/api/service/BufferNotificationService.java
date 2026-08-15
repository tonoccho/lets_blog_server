package com.letsblog.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.api.buffer.BufferClient;
import com.letsblog.api.buffer.BufferUpdate;
import com.letsblog.api.domain.BufferPost;
import com.letsblog.api.repository.BufferPostRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 記事公開をBuffer経由でSNSへ予約投稿する(issue #379)。Bufferの/updates/create.jsonは
 * scheduled_atを指定した時点でBuffer側が予約を引き受けるため、公開から実際の投稿までの
 * 遅延自体を当システムが待つ必要はなく、公開時に1回呼び出すだけでよい。
 * SNS通知の失敗で記事公開自体を失敗させないよう、呼び出し元(PostPublishService)から
 * 非同期(@Async)で呼ばれる想定。
 */
@Service
public class BufferNotificationService {

    private static final Logger log = LoggerFactory.getLogger(BufferNotificationService.class);
    private static final int MAX_ATTEMPTS = 3;

    private final BufferClient bufferClient;
    private final BufferPostRepository bufferPostRepository;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final List<String> profileIds;
    private final int delayMinutes;
    private final String messageTemplate;
    private final long retryBackoffMillis;

    public BufferNotificationService(
            BufferClient bufferClient,
            BufferPostRepository bufferPostRepository,
            ObjectMapper objectMapper,
            @Value("${app.buffer-enabled}") boolean enabled,
            @Value("${app.buffer-profile-ids}") String profileIdsCsv,
            @Value("${app.buffer-post-delay-minutes}") int delayMinutes,
            @Value("${app.buffer-message-template}") String messageTemplate) {
        this(bufferClient, bufferPostRepository, objectMapper, enabled, profileIdsCsv, delayMinutes,
                messageTemplate, 2000L);
    }

    /** テスト専用: リトライ間隔を短縮できるようにするコンストラクタ。 */
    BufferNotificationService(
            BufferClient bufferClient,
            BufferPostRepository bufferPostRepository,
            ObjectMapper objectMapper,
            boolean enabled,
            String profileIdsCsv,
            int delayMinutes,
            String messageTemplate,
            long retryBackoffMillis) {
        this.bufferClient = bufferClient;
        this.bufferPostRepository = bufferPostRepository;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.profileIds = Arrays.stream(profileIdsCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
        this.delayMinutes = delayMinutes;
        this.messageTemplate = messageTemplate;
        this.retryBackoffMillis = retryBackoffMillis;
    }

    /**
     * postId/siteIdに紐づく記事の公開をBufferへ通知する。Buffer未設定(APIキー/プロファイル未設定)の
     * 場合は何もしない。呼び出し元のトランザクション/HTTPレスポンスをブロックしないよう非同期実行する。
     */
    @Async("bufferNotificationExecutor")
    public void notifyAsync(Long postId, Long siteId, String title, String url) {
        if (!enabled || profileIds.isEmpty()) {
            return;
        }

        Instant scheduledAt = Instant.now().plus(delayMinutes, ChronoUnit.MINUTES);
        String text = buildMessage(title, url);

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
                List<BufferUpdate> updates = bufferClient.createUpdate(profileIds, text, scheduledAt);
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

    private String buildMessage(String title, String url) {
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
