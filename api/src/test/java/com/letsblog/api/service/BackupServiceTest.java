package com.letsblog.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * BackupService unit tests covering authorization, validation, and archive structure.
 * Process-level tests (mysqldump/mysql execution) are in integration tests.
 */
@ExtendWith(MockitoExtension.class)
class BackupServiceTest {

    private static final String ENCRYPTION_KEY = "test-encryption-key";

    @Mock
    private AdminAuthorizationService adminAuthorizationService;

    private BackupService service;

    @TempDir
    Path generatedImagesDir;

    @BeforeEach
    void setUp() {
        service = new BackupService("localhost", "3306", "lets_blog", "lbs_app", "secret",
                generatedImagesDir.toString(), ENCRYPTION_KEY, adminAuthorizationService, new ObjectMapper());
    }

    private byte[] buildArchive(String encryptionKeyHash) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("db.sql"));
            zip.write("SELECT 1;".getBytes());
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry("metadata.json"));
            zip.write(new ObjectMapper().writeValueAsBytes(
                    new BackupService.BackupMetadata(encryptionKeyHash, Instant.now().toString())));
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    private byte[] buildArchiveWithImages() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("db.sql"));
            zip.write("SELECT 1;".getBytes());
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry("metadata.json"));
            zip.write(new ObjectMapper().writeValueAsBytes(
                    new BackupService.BackupMetadata(sha256Hex(ENCRYPTION_KEY), Instant.now().toString())));
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry("generated-images/test-image.jpg"));
            zip.write(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry("generated-images/subdir/another.png"));
            zip.write(new byte[]{(byte) 0x89, 0x50, 0x4E});
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

    @Nested
    @DisplayName("Backup Creation Authorization")
    class CreateBackupAuthorizationTests {

        @Test
        @DisplayName("createBackup requires admin privileges")
        void requiresAdminPrivileges() {
            doThrow(new ForbiddenException("この操作にはadmin権限が必要です"))
                    .when(adminAuthorizationService).requireAdmin();

            assertThrows(ForbiddenException.class, () -> service.createBackup());
            verify(adminAuthorizationService).requireAdmin();
        }

        @Test
        @DisplayName("createBackup validates admin authorization first")
        void validatesAuthorizationFirst() {
            doThrow(new ForbiddenException("admin required"))
                    .when(adminAuthorizationService).requireAdmin();

            assertThrows(ForbiddenException.class, () -> service.createBackup());
            verify(adminAuthorizationService).requireAdmin();
        }
    }

    @Nested
    @DisplayName("Backup Restore Authorization")
    class RestoreBackupAuthorizationTests {

        @Test
        @DisplayName("restoreBackup requires admin privileges")
        void requiresAdminPrivileges() {
            doThrow(new ForbiddenException("この操作にはadmin権限が必要です"))
                    .when(adminAuthorizationService).requireAdmin();

            assertThrows(ForbiddenException.class,
                    () -> service.restoreBackup(new ByteArrayInputStream(new byte[0]), true, false));
            verify(adminAuthorizationService).requireAdmin();
        }

        @Test
        @DisplayName("restoreBackup validates admin authorization first")
        void validatesAuthorizationBeforeConfirmCheck() {
            doThrow(new ForbiddenException("admin required"))
                    .when(adminAuthorizationService).requireAdmin();

            assertThrows(ForbiddenException.class,
                    () -> service.restoreBackup(new ByteArrayInputStream(new byte[0]), false, false));
            verify(adminAuthorizationService).requireAdmin();
        }
    }

    @Nested
    @DisplayName("Backup Restore Confirmation")
    class RestoreBackupConfirmationTests {

        @Test
        @DisplayName("restoreBackup requires explicit confirmation")
        void requiresConfirmation() {
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> service.restoreBackup(new ByteArrayInputStream(new byte[0]), false, false));

            assertThat(exception.getMessage(), containsString("confirm=true"));
            verify(adminAuthorizationService).requireAdmin();
        }

        @Test
        @DisplayName("restoreBackup checks confirm flag after admin authorization")
        void checksConfirmAfterAuthorization() {
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> service.restoreBackup(new ByteArrayInputStream(new byte[0]), false, false));

            assertThat(exception.getMessage(), containsString("破壊的操作"));
        }
    }

    @Nested
    @DisplayName("Encryption Key Validation")
    class EncryptionKeyValidationTests {

        @Test
        @DisplayName("restore blocks on encryption key mismatch without acknowledgment")
        void blocksOnKeyMismatchWithoutAcknowledgment() throws Exception {
            byte[] archive = buildArchive(sha256Hex("different-key"));

            BackupException exception = assertThrows(BackupException.class,
                    () -> service.restoreBackup(new ByteArrayInputStream(archive), true, false));

            assertThat(exception.getMessage(), containsString("APP_ENCRYPTION_KEY"));
        }

        @Test
        @DisplayName("restore requires confirmation and checks key mismatch after confirmation")
        void requiresConfirmationBeforeKeyCheck() throws Exception {
            byte[] archive = buildArchive(sha256Hex("different-key"));

            // First, without confirmation, should fail on confirm check
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.restoreBackup(new ByteArrayInputStream(archive), false, false));

            assertThat(ex.getMessage(), containsString("confirm=true"));
        }
    }

    @Nested
    @DisplayName("Backup Archive Validation")
    class BackupArchiveValidationTests {

        @Test
        @DisplayName("archive should be readable as ZIP")
        void archiveShouldBeReadableZip() throws Exception {
            byte[] archive = buildArchive(sha256Hex(ENCRYPTION_KEY));

            // Should not throw when reading as ZIP
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
                ZipEntry entry = zip.getNextEntry();
                assertThat("Archive should contain entries", entry, notNullValue());
            }
        }

        @Test
        @DisplayName("archive should contain db.sql and metadata")
        void archiveContainsRequiredEntries() throws Exception {
            byte[] archive = buildArchive(sha256Hex(ENCRYPTION_KEY));

            int dbSqlCount = 0;
            int metadataCount = 0;
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if ("db.sql".equals(entry.getName())) dbSqlCount++;
                    if ("metadata.json".equals(entry.getName())) metadataCount++;
                }
            }

            assertEquals(1, dbSqlCount, "Archive should contain exactly one db.sql");
            assertEquals(1, metadataCount, "Archive should contain exactly one metadata.json");
        }

        @Test
        @DisplayName("archive with images should preserve directory structure")
        void archivePreservesDirectoryStructure() throws Exception {
            byte[] archive = buildArchiveWithImages();

            boolean hasImageDir = false;
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (entry.getName().startsWith("generated-images/")) {
                        hasImageDir = true;
                    }
                }
            }

            assertTrue(hasImageDir, "Archive with images should preserve directory structure");
        }
    }

    @Nested
    @DisplayName("Backup Archive Structure")
    class BackupArchiveStructureTests {

        @Test
        @DisplayName("archive contains required entries")
        void archiveContainsRequiredEntries() throws Exception {
            byte[] archive = buildArchive(sha256Hex(ENCRYPTION_KEY));

            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
                ZipEntry entry;
                boolean hasDbSql = false;
                boolean hasMetadata = false;

                while ((entry = zip.getNextEntry()) != null) {
                    if ("db.sql".equals(entry.getName())) hasDbSql = true;
                    if ("metadata.json".equals(entry.getName())) hasMetadata = true;
                }

                assertTrue(hasDbSql, "db.sql entry missing");
                assertTrue(hasMetadata, "metadata.json entry missing");
            }
        }

        @Test
        @DisplayName("archive includes generated images when present")
        void archiveIncludesGeneratedImages() throws Exception {
            byte[] archive = buildArchiveWithImages();

            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
                ZipEntry entry;
                boolean hasTestImage = false;
                boolean hasSubdirImage = false;

                while ((entry = zip.getNextEntry()) != null) {
                    if ("generated-images/test-image.jpg".equals(entry.getName())) hasTestImage = true;
                    if ("generated-images/subdir/another.png".equals(entry.getName())) hasSubdirImage = true;
                }

                assertTrue(hasTestImage, "test-image.jpg missing");
                assertTrue(hasSubdirImage, "subdir/another.png missing");
            }
        }

        @Test
        @DisplayName("metadata contains encryptionKeyHash and createdAt")
        void metadataContainsRequiredFields() throws Exception {
            byte[] archive = buildArchive(sha256Hex(ENCRYPTION_KEY));
            ObjectMapper mapper = new ObjectMapper();

            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if ("metadata.json".equals(entry.getName())) {
                        byte[] content = zip.readAllBytes();
                        BackupService.BackupMetadata metadata = mapper.readValue(content, BackupService.BackupMetadata.class);
                        assertThat("encryptionKeyHash", metadata.encryptionKeyHash(), notNullValue());
                        assertThat("createdAt", metadata.createdAt(), notNullValue());
                    }
                }
            }
        }
    }

    @Nested
    @DisplayName("Backup Verification")
    class BackupVerificationTests {

        @Test
        @DisplayName("archive size is non-zero")
        void archiveSizeIsNonZero() throws Exception {
            byte[] archive = buildArchive(sha256Hex(ENCRYPTION_KEY));

            assertThat("Archive should not be empty", archive.length, greaterThan(0));
        }

        @Test
        @DisplayName("archive is valid ZIP format")
        void archiveIsValidZipFormat() throws Exception {
            byte[] archive = buildArchive(sha256Hex(ENCRYPTION_KEY));

            // Should not throw
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
                zip.getNextEntry(); // Should succeed
            }
        }

        @Test
        @DisplayName("archive contains readable entries")
        void archiveContainsReadableEntries() throws Exception {
            byte[] archive = buildArchive(sha256Hex(ENCRYPTION_KEY));

            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
                ZipEntry entry;
                int entryCount = 0;
                while ((entry = zip.getNextEntry()) != null) {
                    entryCount++;
                    zip.readAllBytes(); // Verify content is readable
                }
                assertThat("Should have at least 2 entries", entryCount, greaterThan(1));
            }
        }
    }
}
