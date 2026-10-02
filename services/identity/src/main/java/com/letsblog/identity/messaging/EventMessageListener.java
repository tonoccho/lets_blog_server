package com.letsblog.identity.messaging;

import com.letsblog.common.messaging.ProjectEnvironmentBoundEvent;
import com.letsblog.identity.config.RabbitMqConfig;
import com.letsblog.identity.service.ProjectUserSyncService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * letsblog.events(issue #580)からproject.environment-bound(issue #1324)を受信し、サイトを
 * 紐付ける前に追加されていたメンバーのWordPressユーザーを紐付けたサイトへ補填する。
 * ADR-0004によりproject_usersを持つidentity-serviceはprojectsを参照できないため、この通知が
 * 唯一の契機になる。
 *
 * <p>processed_events(冪等性ストア)を使わない: 補填は、すでに{@code user_site_authors}に対応表が
 * あるメンバーを飛ばすので、同一イベントの再配信に対して自然に冪等であり、記録を持つ必要がない。
 */
@Component
@Slf4j
public class EventMessageListener {

    private final ProjectUserSyncService projectUserSyncService;

    public EventMessageListener(ProjectUserSyncService projectUserSyncService) {
        this.projectUserSyncService = projectUserSyncService;
    }

    @RabbitListener(queues = RabbitMqConfig.PROJECT_ENVIRONMENT_BOUND_QUEUE,
            containerFactory = "eventsListenerContainerFactory")
    public void onProjectEnvironmentBound(ProjectEnvironmentBoundEvent event) {
        projectUserSyncService.backfillMembersToSite(event.projectId(), event.siteId());
        log.info("project.environment-bound処理完了: projectId={}, siteId={}", event.projectId(), event.siteId());
    }
}
