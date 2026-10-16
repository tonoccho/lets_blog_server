package com.letsblog.media.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/** JPAのライフサイクルコールバック({@code @PrePersist}・{@code @PreUpdate})を、永続化層を通さず直接呼んで検証する(issue #1820)。 */
class GeneratedImageSequenceTest {

    @Test
    void コンストラクタはlastSeqを0にしupdatedAtを現在時刻にする() {
        GeneratedImageSequence sequence = new GeneratedImageSequence("p-1");

        assertEquals("p-1", sequence.getProjectKey());
        assertEquals(0, sequence.getLastSeq());
        assertNotNull(sequence.getUpdatedAt());
    }

    @Test
    void onCreateはupdatedAtが無ければ現在時刻を入れる() {
        GeneratedImageSequence sequence = new GeneratedImageSequence();
        LocalDateTime before = LocalDateTime.now();

        sequence.onCreate();

        assertNotNull(sequence.getUpdatedAt());
        assertTrue(!sequence.getUpdatedAt().isBefore(before));
    }

    @Test
    void onCreateはupdatedAtが既にあれば変えない() {
        GeneratedImageSequence sequence = new GeneratedImageSequence();
        LocalDateTime fixed = LocalDateTime.of(2020, 1, 2, 3, 4, 5);
        sequence.setUpdatedAt(fixed);

        sequence.onCreate();

        assertEquals(fixed, sequence.getUpdatedAt());
    }

    @Test
    void onUpdateはupdatedAtを現在時刻にする() {
        GeneratedImageSequence sequence = new GeneratedImageSequence();
        sequence.setUpdatedAt(LocalDateTime.of(2020, 1, 2, 3, 4, 5));
        LocalDateTime before = LocalDateTime.now();

        sequence.onUpdate();

        assertTrue(!sequence.getUpdatedAt().isBefore(before));
    }
}
