package com.letsblog.ai.messaging;

import com.letsblog.ai.config.RabbitMqConfig;
import com.letsblog.ai.repository.ProjectAiSettingsRepository;
import com.letsblog.common.messaging.IdempotentEventHandler;
import com.letsblog.common.messaging.ProjectDeletedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * letsblog.events(issue #580)からproject.deletedを受信し、project_ai_settingsをクリーンアップする。
 * ADR-0004によりprojects(legacy-api)へのクロススキーマFKを持てず、プロジェクト削除時のON DELETE
 * CASCADEが効かないため(project-service未抽出のためlegacy-apiのProjectServiceがproject.deletedを
 * 発行する)、このイベント購読が唯一のクリーンアップ経路になる。
 */
@Component
@Slf4j
public class EventMessageListener {

    private final ProcessedEventStoreImpl processedEventStore;
    private final ProjectAiSettingsRepository projectAiSettingsRepository;

    public EventMessageListener(ProcessedEventStoreImpl processedEventStore,
                                 ProjectAiSettingsRepository projectAiSettingsRepository) {
        this.processedEventStore = processedEventStore;
        this.projectAiSettingsRepository = projectAiSettingsRepository;
    }

    @RabbitListener(queues = RabbitMqConfig.PROJECT_DELETED_QUEUE, containerFactory = "eventsListenerContainerFactory")
    @Transactional
    public void onProjectDeleted(ProjectDeletedEvent event) {
        IdempotentEventHandler.handle(processedEventStore, event, "project.deleted", () -> {
            projectAiSettingsRepository.deleteByProjectId(event.projectId());
            log.info("project.deleted処理完了: projectId={}", event.projectId());
        });
    }
}
