package com.letsblog.content.messaging;

import com.letsblog.common.messaging.IdempotentEventHandler;
import com.letsblog.common.messaging.ImageGeneratedEvent;
import com.letsblog.common.messaging.PostDeletedEvent;
import com.letsblog.common.messaging.PostPublishedEvent;
import com.letsblog.common.messaging.ProjectDeletedEvent;
import com.letsblog.common.messaging.SiteDeletedEvent;
import com.letsblog.common.messaging.UserDeactivatedEvent;
import com.letsblog.content.config.RabbitMqConfig;
import com.letsblog.content.domain.Post;
import com.letsblog.content.repository.PostRepository;
import com.letsblog.content.repository.ProjectContentSettingsRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * letsblog.events(issue #580)からドメインイベントを受信し、content-serviceの責務の範囲で処理する。
 * 全メソッドが{@link IdempotentEventHandler}経由で処理するため、同一eventIdの再配信は二重処理
 * されない(冪等性、受入基準参照)。
 *
 * <p>実データの更新/削除を行うのは{@code project.deleted}(project_content_settings)・
 * {@code site.deleted}(posts)・{@code post.published}/{@code post.deleted}(posts、issue #707で
 * publishing-service抽出とあわせて「記録のみ」から実処理へ切り替えた)の4つ。残り2つ
 * (image.generated/user.deactivated)は、content-service側にまだ具体的な業務アクションが
 * 定義されていないため、processed_eventsへの冪等な受信記録(監査目的、将来の機能追加の土台)のみを行う
 * (docs/EVENT_DRIVEN_ARCHITECTURE.md の「実配線 vs インフラのみ」参照)。
 *
 * <p>ビジネスロジックが例外を投げた場合は握りつぶさずそのまま伝播させる(log-writerの
 * LogMessageListenerと同じ方針)。伝播した例外はspring.rabbitmq.listener.simple.retry設定に従い
 * 再試行され、上限超過後はDLQへ配送される。
 */
@Component
@Slf4j
public class EventMessageListener {

    private final ProcessedEventStoreImpl processedEventStore;
    private final ProjectContentSettingsRepository projectContentSettingsRepository;
    private final PostRepository postRepository;

    public EventMessageListener(ProcessedEventStoreImpl processedEventStore,
                                 ProjectContentSettingsRepository projectContentSettingsRepository,
                                 PostRepository postRepository) {
        this.processedEventStore = processedEventStore;
        this.projectContentSettingsRepository = projectContentSettingsRepository;
        this.postRepository = postRepository;
    }

    @RabbitListener(queues = RabbitMqConfig.PROJECT_DELETED_QUEUE, containerFactory = "eventsListenerContainerFactory")
    @Transactional
    public void onProjectDeleted(ProjectDeletedEvent event) {
        IdempotentEventHandler.handle(processedEventStore, event, "project.deleted", () -> {
            projectContentSettingsRepository.deleteByProjectId(event.projectId());
            log.info("project.deleted処理完了: projectId={}", event.projectId());
        });
    }

    @RabbitListener(queues = RabbitMqConfig.SITE_DELETED_QUEUE, containerFactory = "eventsListenerContainerFactory")
    @Transactional
    public void onSiteDeleted(SiteDeletedEvent event) {
        IdempotentEventHandler.handle(processedEventStore, event, "site.deleted", () -> {
            postRepository.deleteBySiteId(event.siteId());
            log.info("site.deleted処理完了: siteId={}", event.siteId());
        });
    }

    /**
     * publishing-service(issue #707)が投稿公開時に発行する。posts行のstatus/lastPublishedAtを
     * 実際に更新する(以前は「記録のみ」だった。受入基準参照)。該当するposts行は、publishing-service
     * 自身が{@code InternalPostBridgeController#upsert}経由で同期的に既に作成/更新済みのはずだが、
     * 万一未作成のまま届いた場合も取りこぼさないよう、無ければ新規作成する。
     */
    @RabbitListener(queues = RabbitMqConfig.POST_PUBLISHED_QUEUE, containerFactory = "eventsListenerContainerFactory")
    @Transactional
    public void onPostPublished(PostPublishedEvent event) {
        IdempotentEventHandler.handle(processedEventStore, event, "post.published", () -> {
            Post post = postRepository.findBySiteIdAndWpPostId(event.siteId(), event.wpPostId())
                    .orElseGet(Post::new);
            post.setSiteId(event.siteId());
            post.setWpPostId(event.wpPostId());
            post.setStatus(event.status());
            post.setLastPublishedAt(LocalDateTime.now());
            postRepository.save(post);
            log.info("post.published処理完了: siteId={}, wpPostId={}, status={}",
                    event.siteId(), event.wpPostId(), event.status());
        });
    }

    /**
     * publishing-service(issue #707)が投稿削除(ゴミ箱移動)時に発行する。該当するposts行のstatusを
     * "trash"へ更新する(以前は「記録のみ」だった。受入基準参照)。該当行が無ければ何もしない
     * (publishing-service自身が{@code InternalPostBridgeController#markTrashed}経由で同期的に
     * 既に反映済みのはずのため)。
     */
    @RabbitListener(queues = RabbitMqConfig.POST_DELETED_QUEUE, containerFactory = "eventsListenerContainerFactory")
    @Transactional
    public void onPostDeleted(PostDeletedEvent event) {
        IdempotentEventHandler.handle(processedEventStore, event, "post.deleted", () ->
                postRepository.findBySiteIdAndWpPostId(event.siteId(), event.wpPostId()).ifPresentOrElse(post -> {
                    post.setStatus("trash");
                    postRepository.save(post);
                    log.info("post.deleted処理完了: siteId={}, wpPostId={}", event.siteId(), event.wpPostId());
                }, () -> log.info("post.deleted受信: 該当する投稿が見つからないためスキップ: siteId={}, wpPostId={}",
                        event.siteId(), event.wpPostId())));
    }

    @RabbitListener(queues = RabbitMqConfig.IMAGE_GENERATED_QUEUE, containerFactory = "eventsListenerContainerFactory")
    @Transactional
    public void onImageGenerated(ImageGeneratedEvent event) {
        IdempotentEventHandler.handle(processedEventStore, event, "image.generated",
                () -> log.info("image.generated受信(記録のみ): generatedImageId={}, projectId={}",
                        event.generatedImageId(), event.projectId()));
    }

    @RabbitListener(queues = RabbitMqConfig.USER_DEACTIVATED_QUEUE, containerFactory = "eventsListenerContainerFactory")
    @Transactional
    public void onUserDeactivated(UserDeactivatedEvent event) {
        IdempotentEventHandler.handle(processedEventStore, event, "user.deactivated",
                () -> log.info("user.deactivated受信(記録のみ): userId={}", event.userId()));
    }
}
