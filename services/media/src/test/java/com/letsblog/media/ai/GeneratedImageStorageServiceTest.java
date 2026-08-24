package com.letsblog.media.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GeneratedImageStorageServiceTest {

    @Mock
    private GeneratedImageSequenceService sequenceService;

    private GeneratedImageStorageService service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        service = new GeneratedImageStorageService(tempDir.toString(), sequenceService);
    }

    @Test
    void store_プロジェクト指定時は4桁連番ファイル名になる() {
        when(sequenceService.nextSequence("42")).thenReturn(7);

        String relativePath = service.store(42L, new byte[] {1, 2, 3});

        assertEquals("42/0007.png", relativePath);
    }

    @Test
    void store_プロジェクト未指定時はglobal配下に4桁連番ファイル名になる() {
        when(sequenceService.nextSequence("global")).thenReturn(12);

        String relativePath = service.store(null, new byte[] {1, 2, 3});

        assertEquals("global/0012.png", relativePath);
    }
}
