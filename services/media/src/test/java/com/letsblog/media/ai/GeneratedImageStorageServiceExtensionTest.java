package com.letsblog.media.ai;

import com.letsblog.media.domain.GeneratedImageSequence;
import com.letsblog.media.repository.GeneratedImageSequenceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/** issue #1599: 保存する画像の拡張子を指定できる(JPEG保存のため)。 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GeneratedImageStorageService の拡張子指定(issue #1599)")
class GeneratedImageStorageServiceExtensionTest {

    @Mock
    private GeneratedImageSequenceRepository sequenceRepository;

    @TempDir
    Path tempDir;

    private GeneratedImageStorageService service;

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
    @DisplayName("jpgを指定すると4桁連番.jpgで保存される")
    void jpgで保存() throws Exception {
        givenLastSeq("42", 2);

        String path = service.store(42L, new byte[] {9, 8, 7}, "jpg");

        assertEquals("42/0003.jpg", path);
        assertArrayEquals(new byte[] {9, 8, 7}, Files.readAllBytes(tempDir.resolve(path)));
    }
}
