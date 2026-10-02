package com.letsblog.project.messaging;

import com.letsblog.common.messaging.EventExchanges;
import com.letsblog.common.messaging.ProjectDeletedEvent;
import com.letsblog.common.messaging.ProjectEnvironmentBoundEvent;
import com.letsblog.common.messaging.SiteDeletedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.UUID;

/**
 * letsblog.events exchange(issue #580)へのドメインイベント発行。project-serviceの抽出(issue #577)
 * 完了に伴い、project.deleted/site.deletedの発行元を、legacy-api(未抽出の間の代行)からこちらへ
 * 切り替える(legacy-api側の{@code DomainEventPublisher#publishProjectDeleted/publishSiteDeleted}は
 * 削除した)。
 *
 * <p>発行失敗(ブローカー未接続等)は、legacy-api/media-serviceのDomainEventPublisherと同じ方針で
 * ログに記録した上で呼び出し元の処理自体は失敗させない(fire-and-forget)。
 */
@Component
@Slf4j
public class DomainEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    public DomainEventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /** {@code ProjectService#deleteProject}が使う。 */
    public void publishProjectDeleted(Long projectId) {
        publish(EventExchanges.PROJECT_DELETED_ROUTING_KEY,
                new ProjectDeletedEvent(newEventId(), Instant.now(), projectId));
    }

    /** {@code WordPressSiteProvisioningService#deleteSite}が使う。 */
    public void publishSiteDeleted(Long siteId) {
        publish(EventExchanges.SITE_DELETED_ROUTING_KEY,
                new SiteDeletedEvent(newEventId(), Instant.now(), siteId));
    }

    /**
     * {@code ProjectService#bindEnvironment}が使う(issue #1324)。identity-serviceが既存メンバーを補填する。
     *
     * <p>トランザクション内で呼ばれたときは、コミット後にだけ発行する。コミット前に発行すると、
     * identity-serviceが補填のためにproject-serviceへ問い合わせたとき紐付け前の状態を読んだり、
     * ロールバックされた紐付けを通知してしまったりする。トランザクションが無ければ即時に発行する。
     */
    public void publishProjectEnvironmentBound(Long projectId, Long siteId) {
        ProjectEnvironmentBoundEvent event =
                new ProjectEnvironmentBoundEvent(newEventId(), Instant.now(), projectId, siteId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish(EventExchanges.PROJECT_ENVIRONMENT_BOUND_ROUTING_KEY, event);
                }
            });
        } else {
            publish(EventExchanges.PROJECT_ENVIRONMENT_BOUND_ROUTING_KEY, event);
        }
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
