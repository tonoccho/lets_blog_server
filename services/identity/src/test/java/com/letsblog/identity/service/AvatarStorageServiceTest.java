package com.letsblog.identity.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * issue #1241 AC5: アバターを差し替えたとき、直前の画像の実体がボリュームから削除されることの検証。
 *
 * <p>{@link GeneratedImageStorageService}(media-service)と同じ「専用ディレクトリへユーザーID起点の
 * 決定的なパスで書き込む」パターンだが、アバターは1ユーザー1枚のため連番管理は不要で、
 * ユーザーIDそのものをファイル名に使う。差し替えは同じパスへの上書きで実現し、
 * 上書き前のバイト列はディスク上に残らない。
 */
class AvatarStorageServiceTest {

    @TempDir
    Path tempDir;

    private AvatarStorageService service;

    @BeforeEach
    void setUp() {
        service = new AvatarStorageService(tempDir.toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        try (var files = Files.walk(tempDir)) {
            files.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // テスト後片付けのベストエフォート
                }
            });
        }
    }

    @Test
    void 保存した画像を同じユーザーIDで読み込める() {
        byte[] data = "avatar-bytes".getBytes(StandardCharsets.UTF_8);

        service.store(42L, data);
        Optional<byte[]> loaded = service.load(42L);

        assertTrue(loaded.isPresent());
        assertArrayEquals(data, loaded.get());
    }

    @Test
    void 未保存のユーザーの読み込みは空を返す() {
        Optional<byte[]> loaded = service.load(999L);

        assertTrue(loaded.isEmpty());
    }

    @Test
    void 差し替えると直前の画像バイト列はもう読み出せない() {
        byte[] original = "original-avatar".getBytes(StandardCharsets.UTF_8);
        byte[] replacement = "replacement-avatar".getBytes(StandardCharsets.UTF_8);

        service.store(7L, original);
        service.store(7L, replacement);

        Optional<byte[]> loaded = service.load(7L);
        assertTrue(loaded.isPresent());
        assertArrayEquals(replacement, loaded.get());
        assertEquals(1, countFilesFor(7L), "同一ユーザーの実体は1ファイルのみであるべき(前の内容は上書きで消える)");
    }

    private long countFilesFor(long userId) {
        try (var files = Files.list(tempDir)) {
            return files.filter(p -> p.getFileName().toString().startsWith(userId + ".")).count();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
