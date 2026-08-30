package com.letsblog.publishing.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BulkUploadStorageServiceTest {

    @TempDir
    Path tempDir;

    private BulkUploadStorageService service() {
        return new BulkUploadStorageService(tempDir.toString());
    }

    @Test
    void store_保存したファイルを正しいパスとハッシュで読み出せる() throws IOException {
        BulkUploadStorageService service = service();
        byte[] content = "hello".getBytes();

        BulkUploadStorageService.StoredZip stored = service.store(1L, content, "plugin.zip");

        assertEquals("plugin.zip", stored.originalFilename());
        assertTrue(stored.storagePath().startsWith("1/"));
        assertArrayEquals(content, service.load(stored.storagePath()));
    }

    @Test
    void store_同一内容のファイルは重複保存されない() throws IOException {
        BulkUploadStorageService service = service();
        byte[] content = "same-content".getBytes();

        BulkUploadStorageService.StoredZip first = service.store(1L, content, "a.zip");
        BulkUploadStorageService.StoredZip second = service.store(1L, content, "b.zip");

        assertEquals(first.storagePath(), second.storagePath());
        assertEquals(first.sha256(), second.sha256());
        try (var files = Files.list(tempDir.resolve("1"))) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void load_存在しないファイルは例外() {
        BulkUploadStorageService service = service();

        assertThrows(IOException.class, () -> service.load("1/does-not-exist.zip"));
    }

    @Test
    void deleteAll_プロジェクト単位のディレクトリが削除される() throws IOException {
        BulkUploadStorageService service = service();
        service.store(1L, "content".getBytes(), "a.zip");
        assertTrue(Files.exists(tempDir.resolve("1")));

        service.deleteAll(1L);

        assertFalse(Files.exists(tempDir.resolve("1")));
    }
}
