package com.letsblog.content.messaging;

import com.letsblog.common.messaging.ProcessedEventStore;
import com.letsblog.content.domain.ProcessedEvent;
import com.letsblog.content.repository.ProcessedEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ProcessedEventStore}のcontent-service実装(issue #580)。processed_eventsテーブル
 * (event_idがPK=UNIQUE制約)への記録で冪等性を保証する。
 */
@Component
@Slf4j
public class ProcessedEventStoreImpl implements ProcessedEventStore {

    private final ProcessedEventRepository processedEventRepository;

    public ProcessedEventStoreImpl(ProcessedEventRepository processedEventRepository) {
        this.processedEventRepository = processedEventRepository;
    }

    /**
     * 呼び出し元(EventMessageListenerの各{@code @RabbitListener}メソッド)の
     * {@code @Transactional}に参加させる(REQUIRED相当だが、必ずトランザクション内で呼ばれる契約を
     * 明示するためMANDATORYにしている)。
     *
     * <p>まずexistsByIdで確認し(通常の再配信ケースはここで検出できる)、未処理ならsave()するだけで
     * 即時flushはしない。ビジネスロジックと同じトランザクション内でまとめて最終コミット時に
     * flushされるため、ここで例外を捕まえてトランザクションの整合性を壊すことがない。
     * 万一existsByIdチェック後に別インスタンスが同じevent_idを先にコミットしていた場合(まれな
     * 競合、複数インスタンスでの水平スケール時のみ起こりうる)は、コミット時にUNIQUE制約違反で
     * トランザクション全体(ビジネスロジックの効果を含む)がロールバックされる。メッセージは
     * RabbitMQにより再配送され、次回はexistsByIdがtrueを返して正しくスキップされる。
     */
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
