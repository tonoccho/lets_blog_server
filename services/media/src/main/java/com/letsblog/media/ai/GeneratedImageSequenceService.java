package com.letsblog.media.ai;

import com.letsblog.media.domain.GeneratedImageSequence;
import com.letsblog.media.repository.GeneratedImageSequenceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * プロジェクト(またはグローバル)単位で生成画像ファイル名用の4桁連番を払い出すサービス。
 * 先に行を ON DUPLICATE KEY で確保してから行ロック(PESSIMISTIC_WRITE)で採番するため、
 * 同一プロジェクトへの同時採番(初回を含む)でもデッドロック・連番の重複・欠番は発生しない。
 */
@Service
public class GeneratedImageSequenceService {

    private final GeneratedImageSequenceRepository repository;

    public GeneratedImageSequenceService(GeneratedImageSequenceRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public int nextSequence(String projectKey) {
        repository.insertIfAbsent(projectKey);
        GeneratedImageSequence sequence = repository.findByProjectKeyForUpdate(projectKey).orElseThrow();
        int next = sequence.getLastSeq() + 1;
        sequence.setLastSeq(next);
        repository.save(sequence);
        return next;
    }
}
