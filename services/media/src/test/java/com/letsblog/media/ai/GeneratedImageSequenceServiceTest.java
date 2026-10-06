package com.letsblog.media.ai;

import com.letsblog.media.domain.GeneratedImageSequence;
import com.letsblog.media.repository.GeneratedImageSequenceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GeneratedImageSequenceServiceTest {

    @Mock
    private GeneratedImageSequenceRepository repository;

    private GeneratedImageSequenceService service;

    @BeforeEach
    void setUp() {
        service = new GeneratedImageSequenceService(repository);
    }

    @Test
    void nextSequence_既存行があれば連番をインクリメントする() {
        GeneratedImageSequence existing = new GeneratedImageSequence("global");
        existing.setLastSeq(3);
        when(repository.findByProjectKeyForUpdate("global")).thenReturn(Optional.of(existing));

        int result = service.nextSequence("global");

        assertEquals(4, result);
        assertEquals(4, existing.getLastSeq());
    }

    @Test
    void nextSequence_行が存在しない場合は先に行を確保してから1を返す() {
        GeneratedImageSequence created = new GeneratedImageSequence("12");
        when(repository.findByProjectKeyForUpdate("12")).thenReturn(Optional.of(created));

        int result = service.nextSequence("12");

        assertEquals(1, result);
        verify(repository).insertIfAbsent("12");
    }
}
