package com.letsblog.media.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/** issue #1599: 保存する画像の拡張子を指定できる(JPEG保存のため)。 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GeneratedImageStorageService の拡張子指定(issue #1599)")
class GeneratedImageStorageServiceExtensionTest {

    @Mock
    private GeneratedImageSequenceService sequenceService;

    @TempDir
    Path tempDir;

    private GeneratedImageStorageService service;

    @BeforeEach
    void setUp() {
        service = new GeneratedImageStorageService(tempDir.toString(), sequenceService);
    }

    @Test
    @DisplayName("jpgを指定すると4桁連番.jpgで保存される")
    void jpgで保存() throws Exception {
        when(sequenceService.nextSequence("42")).thenReturn(3);

        String path = service.store(42L, new byte[] {9, 8, 7}, "jpg");

        assertEquals("42/0003.jpg", path);
        assertArrayEquals(new byte[] {9, 8, 7}, Files.readAllBytes(tempDir.resolve(path)));
    }
}
