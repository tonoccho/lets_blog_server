package com.letsblog.media.ai;

import com.letsblog.media.domain.GeneratedImageSequence;
import com.letsblog.media.repository.GeneratedImageSequenceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * プロジェクト(またはグローバル)単位で生成画像ファイル名用の4桁連番を払い出すサービス。
 * 行ロック(PESSIMISTIC_WRITE)で採番するため、同一プロジェクトへの同時採番でも
 * 連番の重複・欠番は発生しない。初回のみ行が存在せず、その作成が同時に競合する
 * 可能性があるため、呼び出し側(GeneratedImageStorageService)で一意制約違反時の
 * リトライを行う。
 */
@Service
public class GeneratedImageSequenceService {

    private final GeneratedImageSequenceRepository repository;

    public GeneratedImageSequenceService(GeneratedImageSequenceRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public int nextSequence(String projectKey) {
        GeneratedImageSequence sequence = repository.findByProjectKeyForUpdate(projectKey)
                .orElseGet(() -> repository.saveAndFlush(new GeneratedImageSequence(projectKey)));
        int next = sequence.getLastSeq() + 1;
        sequence.setLastSeq(next);
        repository.save(sequence);
        return next;
    }
}
