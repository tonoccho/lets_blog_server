package com.letsblog.media.messaging;

import com.letsblog.common.messaging.EventExchanges;
import com.letsblog.common.messaging.ImageGeneratedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * letsblog.events exchange(issue #580)へのドメインイベント発行。media-serviceは
 * image.generatedイベントの発行元(GeneratedImageController#create、ComfyUI/ChatGPTでの画像生成完了・
 * DB行作成が終わった時点)を担う。
 *
 * <p>発行失敗(ブローカー未接続等)は、legacy-apiのDomainEventPublisher/media-serviceの
 * AuditLogServiceと同じ方針でログに記録した上で呼び出し元の処理自体は失敗させない
 * (fire-and-forget、画像自体の保存は既に成功しているため)。
 */
@Component
@Slf4j
public class DomainEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    public DomainEventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publishImageGenerated(Long generatedImageId, Long projectId) {
        String imageUrl = "/api/generated-images/" + generatedImageId + "/file";
        ImageGeneratedEvent event = new ImageGeneratedEvent(
                UUID.randomUUID().toString(), Instant.now(), generatedImageId, projectId, imageUrl);
        try {
            rabbitTemplate.convertAndSend(
                    EventExchanges.EVENTS_EXCHANGE, EventExchanges.IMAGE_GENERATED_ROUTING_KEY, event);
            log.info("Domain event published: routingKey={}, event={}",
                    EventExchanges.IMAGE_GENERATED_ROUTING_KEY, event);
        } catch (AmqpException e) {
            log.error("ドメインイベントのキュー発行に失敗しました: routingKey={}, event={}",
                    EventExchanges.IMAGE_GENERATED_ROUTING_KEY, event, e);
        }
    }
}
