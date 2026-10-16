package com.letsblog.media.ai;

import com.letsblog.media.domain.GeneratedImageSequence;
import com.letsblog.media.repository.GeneratedImageSequenceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GeneratedImageStorageServiceTest {

    @Mock
    private GeneratedImageSequenceRepository sequenceRepository;

    private GeneratedImageStorageService service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        service = new GeneratedImageStorageService(
                tempDir.toString(), new GeneratedImageSequenceService(sequenceRepository));
    }

    /** 採番テーブルの行(DB)を、{@code lastSeq}まで払い出し済みの状態で返すようにする。 */
    private void givenLastSeq(String projectKey, int lastSeq) {
        GeneratedImageSequence row = new GeneratedImageSequence(projectKey);
        row.setLastSeq(lastSeq);
        when(sequenceRepository.findByProjectKeyForUpdate(projectKey)).thenReturn(Optional.of(row));
    }

    @Test
    void store_プロジェクト指定時は4桁連番ファイル名になる() {
        givenLastSeq("42", 6);

        String relativePath = service.store(42L, new byte[] {1, 2, 3});

        assertEquals("42/0007.png", relativePath);
    }

    @Test
    void store_プロジェクト未指定時はglobal配下に4桁連番ファイル名になる() {
        givenLastSeq("global", 11);

        String relativePath = service.store(null, new byte[] {1, 2, 3});

        assertEquals("global/0012.png", relativePath);
    }
}
