package com.letsblog.content.messaging;

import com.letsblog.common.messaging.IdempotentEventHandler;
import com.letsblog.common.messaging.ImageGeneratedEvent;
import com.letsblog.common.messaging.PostDeletedEvent;
import com.letsblog.common.messaging.PostPublishedEvent;
import com.letsblog.common.messaging.ProjectDeletedEvent;
import com.letsblog.common.messaging.SiteDeletedEvent;
import com.letsblog.common.messaging.UserDeactivatedEvent;
import com.letsblog.content.config.RabbitMqConfig;
import com.letsblog.content.repository.PostRepository;
import com.letsblog.content.repository.ProjectContentSettingsRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * letsblog.events(issue #580)からドメインイベントを受信し、content-serviceの責務の範囲で処理する。
 * 全メソッドが{@link IdempotentEventHandler}経由で処理するため、同一eventIdの再配信は二重処理
 * されない(冪等性、受入基準参照)。
 *
 * <p>実データの削除を行うのは{@code project.deleted}(project_content_settings)と
 * {@code site.deleted}(posts)の2つ。残り4つ(post.published/post.deleted/image.generated/
 * user.deactivated)は、content-service側にまだ具体的な業務アクションが定義されていないため、
 * processed_eventsへの冪等な受信記録(監査目的、将来の機能追加の土台)のみを行う
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

    @RabbitListener(queues = RabbitMqConfig.POST_PUBLISHED_QUEUE, containerFactory = "eventsListenerContainerFactory")
    @Transactional
    public void onPostPublished(PostPublishedEvent event) {
        IdempotentEventHandler.handle(processedEventStore, event, "post.published",
                () -> log.info("post.published受信(記録のみ): siteId={}, wpPostId={}", event.siteId(), event.wpPostId()));
    }

    @RabbitListener(queues = RabbitMqConfig.POST_DELETED_QUEUE, containerFactory = "eventsListenerContainerFactory")
    @Transactional
    public void onPostDeleted(PostDeletedEvent event) {
        IdempotentEventHandler.handle(processedEventStore, event, "post.deleted",
                () -> log.info("post.deleted受信(記録のみ): siteId={}, wpPostId={}", event.siteId(), event.wpPostId()));
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
