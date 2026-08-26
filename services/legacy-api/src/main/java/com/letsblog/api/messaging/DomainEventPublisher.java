package com.letsblog.api.messaging;

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
 * letsblog.events exchange(issue #580)へのドメインイベント発行。publishing-serviceはまだ抽出されて
 * いないため(ADR-0001)、そのドメインロジックが引き続き存在するlegacy-apiがpost.published/post.deleted
 * の発行元を代行する。project.deleted/site.deletedは、project-serviceの抽出(issue #577)完了に伴い
 * こちらから削除し、project-service自身の{@code DomainEventPublisher}へ発行元を切り替えた
 * (legacy-api側はもうProject/Siteの削除処理自体を持たない)。
 *
 * <p>発行失敗(ブローカー未接続等)は、監査ログ発行(AuditLogService)と同じ方針でログに記録した上で
 * 呼び出し元の処理自体は失敗させない(fire-and-forget)。イベント配送そのものの信頼性は
 * コンシューマー側のDLQ・リトライで担保する設計であり、プロデューサー側の送信失敗は現状スコープ外
 * (将来的にoutboxパターン等での改善余地がある)。
 */
@Component
@Slf4j
public class DomainEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    public DomainEventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /** publishing-service(未抽出のためPostPublishServiceが代行)が投稿公開時に発行する。 */
    public void publishPostPublished(Long siteId, Long projectId, String wpPostId, String url, String status) {
        publish(EventExchanges.POST_PUBLISHED_ROUTING_KEY,
                new PostPublishedEvent(newEventId(), Instant.now(), siteId, projectId, wpPostId, url, status));
    }

    /** publishing-service(未抽出のためPostDeleteServiceが代行)が投稿削除(ゴミ箱移動)時に発行する。 */
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
