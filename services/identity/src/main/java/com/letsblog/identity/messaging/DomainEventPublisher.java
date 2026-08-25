package com.letsblog.identity.messaging;

import com.letsblog.common.messaging.EventExchanges;
import com.letsblog.common.messaging.UserDeactivatedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * letsblog.events exchange(issue #580)へのドメインイベント発行。identity-serviceは
 * user.deactivatedイベントの発行元(UserService#deactivate)を担う。
 *
 * <p>発行失敗(ブローカー未接続等)は他サービスのDomainEventPublisherと同じ方針でログに記録した上で
 * 呼び出し元の処理自体は失敗させない(fire-and-forget、ユーザー無効化自体は既に成功しているため)。
 */
@Component
@Slf4j
public class DomainEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    public DomainEventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publishUserDeactivated(Long userId, String keycloakSub) {
        UserDeactivatedEvent event = new UserDeactivatedEvent(
                UUID.randomUUID().toString(), Instant.now(), userId, keycloakSub);
        try {
            rabbitTemplate.convertAndSend(
                    EventExchanges.EVENTS_EXCHANGE, EventExchanges.USER_DEACTIVATED_ROUTING_KEY, event);
            log.info("Domain event published: routingKey={}, event={}",
                    EventExchanges.USER_DEACTIVATED_ROUTING_KEY, event);
        } catch (AmqpException e) {
            log.error("ドメインイベントのキュー発行に失敗しました: routingKey={}, event={}",
                    EventExchanges.USER_DEACTIVATED_ROUTING_KEY, event, e);
        }
    }
}
