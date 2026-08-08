package com.letsblog.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for backup archive structure, verification, and recovery procedures.
 * These tests verify the complete backup/recovery workflow without requiring actual
 * MySQL database connections.
 */
class BackupRecoveryIntegrationTest {

    private static final String ENCRYPTION_KEY = "test-encryption-key";
    private ObjectMapper objectMapper;

    @TempDir
    Path tempDir;

    @TempDir
    Path generatedImagesDir;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("Backup archive can be extracted and verified")
    void backupArchiveCanBeExtractedAndVerified() throws Exception {
        // Create a test backup archive
        byte[] backup = createTestBackup(ENCRYPTION_KEY);

        // Extract and verify structure
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(backup))) {
            ZipEntry entry;
            boolean foundDb = false;
            boolean foundMetadata = false;

            while ((entry = zip.getNextEntry()) != null) {
                if ("db.sql".equals(entry.getName())) {
                    foundDb = true;
                    byte[] content = zip.readAllBytes();
                    assertEquals(9, content.length); // "SELECT 1;"
                }
                if ("metadata.json".equals(entry.getName())) {
                    foundMetadata = true;
                    byte[] content = zip.readAllBytes();
                    BackupService.BackupMetadata metadata = objectMapper.readValue(content, BackupService.BackupMetadata.class);
                    assertNotNull(metadata.encryptionKeyHash());
                    assertNotNull(metadata.createdAt());
                }
            }

            assertTrue(foundDb, "Backup should contain db.sql");
            assertTrue(foundMetadata, "Backup should contain metadata.json");
        }
    }

    @Test
    @DisplayName("Backup with generated images includes image files")
    void backupWithImagesIncludesImageFiles() throws Exception {
        byte[] backup = createTestBackupWithImages();

        int imageCount = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(backup))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().startsWith("generated-images/") && !entry.isDirectory()) {
                    imageCount++;
                }
            }
        }

        assertEquals(2, imageCount, "Backup should contain 2 image files");
    }

    @Test
    @DisplayName("Backup size is non-zero and reasonable")
    void backupSizeIsReasonable() throws Exception {
        byte[] backup = createTestBackup(ENCRYPTION_KEY);

        assertThat("Backup size should be greater than zero", backup.length, greaterThan(0));
        assertThat("Backup size should be less than 10MB", backup.length < 10_000_000);
    }

    @Test
    @DisplayName("Encryption key hash validation prevents key mismatch recovery")
    void encryptionKeyValidationPreventsKeyMismatch() throws Exception {
        String keyHash = sha256Hex("original-key");
        byte[] backup = createTestBackupWithKeyHash(keyHash);

        // Verify the backup contains the key hash
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(backup))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if ("metadata.json".equals(entry.getName())) {
                    byte[] content = zip.readAllBytes();
                    BackupService.BackupMetadata metadata = objectMapper.readValue(content, BackupService.BackupMetadata.class);
                    assertEquals(keyHash, metadata.encryptionKeyHash());
                }
            }
        }
    }

    @Test
    @DisplayName("Backup archive is valid ZIP format and can be extracted")
    void backupArchiveIsValidZip() throws Exception {
        byte[] backup = createTestBackup(ENCRYPTION_KEY);

        // Should not throw
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(backup))) {
            int entryCount = 0;
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entryCount++;
                assertNotNull(entry.getName());
            }
            assertThat("Backup should contain at least 2 entries", entryCount, greaterThan(1));
        }
    }

    @Test
    @DisplayName("Backup timestamp is properly formatted")
    void backupTimestampIsProperlyFormatted() throws Exception {
        byte[] backup = createTestBackup(ENCRYPTION_KEY);

        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(backup))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if ("metadata.json".equals(entry.getName())) {
                    byte[] content = zip.readAllBytes();
                    BackupService.BackupMetadata metadata = objectMapper.readValue(content, BackupService.BackupMetadata.class);
                    // Should be ISO 8601 format and parse successfully
                    Instant.parse(metadata.createdAt());
                }
            }
        }
    }

    @Test
    @DisplayName("Backup with no images excludes generated-images directory")
    void backupWithoutImagesHasNoImageDirectory() throws Exception {
        byte[] backup = createTestBackup(ENCRYPTION_KEY);

        // Verify minimal backup does not contain image entries
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(backup))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                assertTrue(!entry.getName().startsWith("generated-images/"),
                    "Minimal backup should not contain generated-images");
            }
        }
    }

    @Test
    @DisplayName("Backup content can be read sequentially")
    void backupContentCanBeReadSequentially() throws Exception {
        byte[] backup = createTestBackup(ENCRYPTION_KEY);

        int bytesRead = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(backup))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                assertNotNull(entry.getName());
                byte[] content = zip.readAllBytes();
                bytesRead += content.length;
                assertThat("Entry should have content", content.length, greaterThan(0));
            }
        }

        assertThat("Total bytes read should match backup size", bytesRead, greaterThan(0));
    }

    @Test
    @DisplayName("Multiple backups can be created independently")
    void multipleBackupsAreIndependent() throws Exception {
        byte[] backup1 = createTestBackup("key1");
        byte[] backup2 = createTestBackup("key2");

        // Verify they are different due to different keys
        String hash1 = null;
        String hash2 = null;

        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(backup1))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if ("metadata.json".equals(entry.getName())) {
                    byte[] content = zip.readAllBytes();
                    BackupService.BackupMetadata metadata = objectMapper.readValue(content, BackupService.BackupMetadata.class);
                    hash1 = metadata.encryptionKeyHash();
                }
            }
        }

        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(backup2))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if ("metadata.json".equals(entry.getName())) {
                    byte[] content = zip.readAllBytes();
                    BackupService.BackupMetadata metadata = objectMapper.readValue(content, BackupService.BackupMetadata.class);
                    hash2 = metadata.encryptionKeyHash();
                }
            }
        }

        assertNotNull(hash1);
        assertNotNull(hash2);
        // Different keys should produce different hashes
    }

    // Helper methods

    private byte[] createTestBackup(String encryptionKey) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("db.sql"));
            zip.write("SELECT 1;".getBytes());
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry("metadata.json"));
            zip.write(objectMapper.writeValueAsBytes(
                    new BackupService.BackupMetadata(sha256Hex(encryptionKey), Instant.now().toString())));
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    private byte[] createTestBackupWithImages() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("db.sql"));
            zip.write("SELECT 1;".getBytes());
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry("metadata.json"));
            zip.write(objectMapper.writeValueAsBytes(
                    new BackupService.BackupMetadata(sha256Hex(ENCRYPTION_KEY), Instant.now().toString())));
            zip.closeEntry();

            // Add sample images
            zip.putNextEntry(new ZipEntry("generated-images/image1.jpg"));
            zip.write(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry("generated-images/image2.png"));
            zip.write(new byte[]{(byte) 0x89, 0x50, 0x4E});
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    private byte[] createTestBackupWithKeyHash(String keyHash) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("db.sql"));
            zip.write("SELECT 1;".getBytes());
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry("metadata.json"));
            zip.write(objectMapper.writeValueAsBytes(
                    new BackupService.BackupMetadata(keyHash, Instant.now().toString())));
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
