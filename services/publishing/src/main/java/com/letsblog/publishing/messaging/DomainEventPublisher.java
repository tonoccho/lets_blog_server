package com.letsblog.publishing.messaging;

import com.letsblog.common.messaging.EventExchanges;
import com.letsblog.common.messaging.PostDeletedEvent;
import com.letsblog.common.messaging.PostPublishedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * letsblog.events exchange(issue #580)へのドメインイベント発行。post.published/post.deletedの
 * 発行元をlegacy-apiの{@code DomainEventPublisher}(PostPublishService/PostDeleteServiceが暫定的に
 * 代行していたもの)からpublishing-service自身へ移した(issue #707、#575設計判断3)。
 *
 * <p>発行失敗(ブローカー未接続等)は、legacy-api版と同じ方針でログに記録した上で呼び出し元の処理自体は
 * 失敗させない(fire-and-forget)。
 */
@Component
@Slf4j
public class DomainEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    public DomainEventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /** PostPublishService#publishが投稿公開時に発行する。 */
    public void publishPostPublished(Long siteId, Long projectId, String wpPostId, String url, String status) {
        publish(EventExchanges.POST_PUBLISHED_ROUTING_KEY,
                new PostPublishedEvent(newEventId(), Instant.now(), siteId, projectId, wpPostId, url, status));
    }

    /** PostDeleteService#deleteが投稿削除(ゴミ箱移動)時に発行する。 */
    public void publishPostDeleted(Long siteId, String wpPostId) {
        publish(EventExchanges.POST_DELETED_ROUTING_KEY,
                new PostDeletedEvent(newEventId(), Instant.now(), siteId, wpPostId));
    }

    private void publish(String routingKey, Object event) {
        try {
            rabbitTemplate.convertAndSend(EventExchanges.EVENTS_EXCHANGE, routingKey, event);
            log.info("Domain event published: routingKey={}, event={}", routingKey, event);
        } catch (AmqpException e) {
            log.error("ドメインイベントのキュー発行に失敗しました: routingKey={}, event={}", routingKey, event, e);
        }
    }

    private String newEventId() {
        return UUID.randomUUID().toString();
    }
}
