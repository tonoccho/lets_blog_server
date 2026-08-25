package com.letsblog.analytics.messaging;

import com.letsblog.analytics.domain.ProcessedEvent;
import com.letsblog.analytics.repository.ProcessedEventRepository;
import com.letsblog.common.messaging.ProcessedEventStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ProcessedEventStore}のanalytics-service実装(issue #580)。content-serviceの
 * ProcessedEventStoreImplと同じ設計(existsById→save、即時flushしない)。詳細はそちらのJavadoc参照。
 */
@Component
@Slf4j
public class ProcessedEventStoreImpl implements ProcessedEventStore {

    private final ProcessedEventRepository processedEventRepository;

    public ProcessedEventStoreImpl(ProcessedEventRepository processedEventRepository) {
        this.processedEventRepository = processedEventRepository;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markIfNotProcessed(String eventId, String eventType) {
        if (processedEventRepository.existsById(eventId)) {
            log.info("イベントは既に処理済みのためスキップします(重複配信): eventId={}, eventType={}", eventId, eventType);
            return false;
        }
        processedEventRepository.save(new ProcessedEvent(eventId, eventType));
        return true;
    }
}
