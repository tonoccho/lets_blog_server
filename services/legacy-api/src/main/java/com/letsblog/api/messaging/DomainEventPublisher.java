package com.letsblog.api.messaging;

import com.letsblog.common.messaging.EventExchanges;
import com.letsblog.common.messaging.PostDeletedEvent;
import com.letsblog.common.messaging.PostPublishedEvent;
import com.letsblog.common.messaging.ProjectDeletedEvent;
import com.letsblog.common.messaging.SiteDeletedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * letsblog.events exchange(issue #580)へのドメインイベント発行。project-service/publishing-serviceは
 * まだ抽出されていないため(ADR-0001)、それらのドメインロジックが引き続き存在するlegacy-apiが
 * project.deleted/site.deleted/post.published/post.deletedの発行元を代行する。
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

    /** project-service(未抽出のためProjectServiceが代行)がプロジェクト削除時に発行する。 */
    public void publishProjectDeleted(Long projectId) {
        publish(EventExchanges.PROJECT_DELETED_ROUTING_KEY,
                new ProjectDeletedEvent(newEventId(), Instant.now(), projectId));
    }

    /** project-service(未抽出のためWordPressSiteProvisioningServiceが代行)がサイト削除時に発行する。 */
    public void publishSiteDeleted(Long siteId) {
        publish(EventExchanges.SITE_DELETED_ROUTING_KEY,
                new SiteDeletedEvent(newEventId(), Instant.now(), siteId));
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
