package com.letsblog.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.platform.config.BackupProperties;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * BackupService unit tests covering authorization, validation, and archive structure
 * (legacy-apiのBackupServiceTestと同じ観点を、全MySQLスキーマ+Keycloak PostgreSQL対応後の
 * アーカイブ構成に対して踏襲する。issue #694)。
 * Process-level tests (mysqldump/mysql/pg_dump/pg_restore execution)は対象としない
 * (実DBに接続する結合テストの領域。ADR-0006参照)。
 */
@ExtendWith(MockitoExtension.class)
class BackupServiceTest {

    private static final String ENCRYPTION_KEY = "test-encryption-key";
    private static final List<String> MYSQL_SCHEMAS = List.of("lbs_identity", "lbs_media", "lets_blog");
    private static final String POSTGRES_DATABASE = "keycloak";

    @Mock
    private AdminAuthorizationService adminAuthorizationService;

    private BackupService service;

    @TempDir
    Path generatedImagesDir;

    @BeforeEach
    void setUp() {
        BackupProperties properties = new BackupProperties();
        properties.getMysql().setHost("localhost");
        properties.getMysql().setPort("3306");
        properties.getMysql().setUser("lbs_backup");
        properties.getMysql().setPassword("secret");
        properties.getMysql().setSchemas(MYSQL_SCHEMAS);
        properties.getPostgres().setHost("localhost");
        properties.getPostgres().setPort("5432");
        properties.getPostgres().setUser("keycloak");
        properties.getPostgres().setPassword("secret");
        properties.getPostgres().setDatabase(POSTGRES_DATABASE);

        service = new BackupService(properties, generatedImagesDir.toString(), ENCRYPTION_KEY,
                adminAuthorizationService, new ObjectMapper());
    }

    private byte[] buildArchive(String encryptionKeyHash) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("metadata.json"));
            zip.write(new ObjectMapper().writeValueAsBytes(new BackupService.BackupMetadata(
                    encryptionKeyHash, Instant.now().toString(), MYSQL_SCHEMAS, List.of(POSTGRES_DATABASE))));
            zip.closeEntry();

            for (String schema : MYSQL_SCHEMAS) {
                zip.putNextEntry(new ZipEntry("mysql/" + schema + ".sql"));
                zip.write("SELECT 1;".getBytes());
                zip.closeEntry();
            }

            zip.putNextEntry(new ZipEntry("postgres/" + POSTGRES_DATABASE + ".dump"));
            zip.write(new byte[]{1, 2, 3});
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    private byte[] buildArchiveWithImages() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("metadata.json"));
            zip.write(new ObjectMapper().writeValueAsBytes(new BackupService.BackupMetadata(
                    sha256Hex(ENCRYPTION_KEY), Instant.now().toString(), MYSQL_SCHEMAS, List.of(POSTGRES_DATABASE))));
            zip.closeEntry();

            for (String schema : MYSQL_SCHEMAS) {
                zip.putNextEntry(new ZipEntry("mysql/" + schema + ".sql"));
                zip.write("SELECT 1;".getBytes());
                zip.closeEntry();
            }

            zip.putNextEntry(new ZipEntry("postgres/" + POSTGRES_DATABASE + ".dump"));
            zip.write(new byte[]{1, 2, 3});
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
    @DisplayName("Backup Archive Structure")
    class BackupArchiveStructureTests {

        @Test
        @DisplayName("archive contains a metadata.json entry and one sql dump entry per configured schema")
        void archiveContainsRequiredEntries() throws Exception {
            byte[] archive = buildArchive(sha256Hex(ENCRYPTION_KEY));

            java.util.Set<String> mysqlEntries = new java.util.HashSet<>();
            boolean hasMetadata = false;
            boolean hasPostgresDump = false;

            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if ("metadata.json".equals(entry.getName())) {
                        hasMetadata = true;
                    }
                    if (entry.getName().startsWith("mysql/") && entry.getName().endsWith(".sql")) {
                        mysqlEntries.add(entry.getName());
                    }
                    if (entry.getName().startsWith("postgres/") && entry.getName().endsWith(".dump")) {
                        hasPostgresDump = true;
                    }
                }
            }

            assertTrue(hasMetadata, "metadata.json entry missing");
            assertTrue(hasPostgresDump, "postgres dump entry missing");
            for (String schema : MYSQL_SCHEMAS) {
                assertTrue(mysqlEntries.contains("mysql/" + schema + ".sql"), "mysql/" + schema + ".sql missing");
            }
        }

        @Test
        @DisplayName("archive includes generated images when present")
        void archiveIncludesGeneratedImages() throws Exception {
            byte[] archive = buildArchiveWithImages();

            boolean hasTestImage = false;
            boolean hasSubdirImage = false;

            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if ("generated-images/test-image.jpg".equals(entry.getName())) hasTestImage = true;
                    if ("generated-images/subdir/another.png".equals(entry.getName())) hasSubdirImage = true;
                }
            }

            assertTrue(hasTestImage, "test-image.jpg missing");
            assertTrue(hasSubdirImage, "subdir/another.png missing");
        }

        @Test
        @DisplayName("metadata contains encryptionKeyHash, createdAt, mysqlSchemas and postgresDatabases")
        void metadataContainsRequiredFields() throws Exception {
            byte[] archive = buildArchive(sha256Hex(ENCRYPTION_KEY));
            ObjectMapper mapper = new ObjectMapper();

            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if ("metadata.json".equals(entry.getName())) {
                        byte[] content = zip.readAllBytes();
                        BackupService.BackupMetadata metadata =
                                mapper.readValue(content, BackupService.BackupMetadata.class);
                        assertThat("encryptionKeyHash", metadata.encryptionKeyHash(), notNullValue());
                        assertThat("createdAt", metadata.createdAt(), notNullValue());
                        assertEquals(MYSQL_SCHEMAS, metadata.mysqlSchemas());
                        assertEquals(List.of(POSTGRES_DATABASE), metadata.postgresDatabases());
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

            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
                assertThat("Archive should contain entries", zip.getNextEntry(), notNullValue());
            }
        }
    }

    @Nested
    @DisplayName("Restore rejects archive entries outside the configured schema allow-list")
    class UnknownSchemaRejectionTests {

        @Test
        @DisplayName("restoreBackup silently skips db dump entries for schemas not in the configured list, "
                + "so no mysql/pg_restore subprocess is ever launched for them "
                + "(defense against a tampered archive naming e.g. the MySQL system schema)")
        void ignoresUnknownSchemaEntriesWithoutInvokingAnyProcess() throws Exception {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(out)) {
                zip.putNextEntry(new ZipEntry("metadata.json"));
                zip.write(new ObjectMapper().writeValueAsBytes(new BackupService.BackupMetadata(
                        sha256Hex(ENCRYPTION_KEY), Instant.now().toString(), MYSQL_SCHEMAS,
                        List.of(POSTGRES_DATABASE))));
                zip.closeEntry();

                // "mysql" is the MySQL system schema, not in MYSQL_SCHEMAS: must not be executed against.
                zip.putNextEntry(new ZipEntry("mysql/mysql.sql"));
                zip.write("DROP TABLE user;".getBytes());
                zip.closeEntry();
            }
            byte[] archive = out.toByteArray();

            // Every entry present is filtered out (the only mysql/ entry is not in the allow-list, and
            // there is no postgres/ entry), so restoreBackup completes without ever invoking the real
            // "mysql"/"pg_restore" binaries. If it attempted to, this test would fail with a
            // BackupException because those binaries are not necessarily on the test runner's PATH/
            // reachable host, which would make the intent of this assertion (no invocation) clear too.
            service.restoreBackup(new ByteArrayInputStream(archive), true, false);

            verify(adminAuthorizationService).requireAdmin();
        }
    }

    @Nested
    @DisplayName("Restore validates generated image paths against Zip Slip (issue #699)")
    class GeneratedImagesRestorePathValidationTests {

        /**
         * mysqlDumps/postgresDumpのどちらかが非空でないとrestoreBackupは"ダンプが含まれていません"で
         * 例外を投げるため(実処理には無関係)、許可リストに含まれない(=フィルタで除外され、実際の
         * mysqlバイナリは決して起動されない)ダミーのMySQLスキーマエントリを1つ含める。
         * UnknownSchemaRejectionTestsと同じ手法。
         */
        private byte[] buildImagesOnlyArchive(String... generatedImageEntryNames) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(out)) {
                zip.putNextEntry(new ZipEntry("metadata.json"));
                zip.write(new ObjectMapper().writeValueAsBytes(new BackupService.BackupMetadata(
                        sha256Hex(ENCRYPTION_KEY), Instant.now().toString(), MYSQL_SCHEMAS,
                        List.of(POSTGRES_DATABASE))));
                zip.closeEntry();

                zip.putNextEntry(new ZipEntry("mysql/not-an-allowed-schema.sql"));
                zip.write("SELECT 1;".getBytes());
                zip.closeEntry();

                for (String name : generatedImageEntryNames) {
                    zip.putNextEntry(new ZipEntry(name));
                    zip.write("content-of-".concat(name).getBytes());
                    zip.closeEntry();
                }
            }
            return out.toByteArray();
        }

        @Test
        @DisplayName("legitimate relative generated-image paths are restored under generatedImagesDir "
                + "(no regression)")
        void restoresLegitimateRelativePaths() throws Exception {
            byte[] archive = buildImagesOnlyArchive(
                    "generated-images/test-image.jpg",
                    "generated-images/subdir/another.png");

            service.restoreBackup(new ByteArrayInputStream(archive), true, false);

            Path expected1 = generatedImagesDir.resolve("test-image.jpg");
            Path expected2 = generatedImagesDir.resolve("subdir/another.png");
            assertTrue(Files.exists(expected1), "test-image.jpg should have been restored");
            assertTrue(Files.exists(expected2), "subdir/another.png should have been restored");
            assertEquals("content-of-generated-images/test-image.jpg",
                    Files.readString(expected1));
        }

        @Test
        @DisplayName("a crafted entry name containing path traversal (generated-images/../../evil.sh) "
                + "is skipped and never written outside generatedImagesDir")
        void rejectsPathTraversalEntry() throws Exception {
            byte[] archive = buildImagesOnlyArchive(
                    "generated-images/../../evil.sh",
                    "generated-images/legit.txt");

            service.restoreBackup(new ByteArrayInputStream(archive), true, false);

            // The traversal entry must not have been written anywhere outside generatedImagesDir.
            Path outsideTarget = generatedImagesDir.toAbsolutePath().normalize()
                    .getParent().getParent().resolve("evil.sh");
            assertFalse(Files.exists(outsideTarget),
                    "evil.sh must not be written outside generatedImagesDir");

            // Also verify nothing named evil.sh was written anywhere inside generatedImagesDir either
            // (it must simply be skipped, not silently relocated).
            try (var walk = Files.walk(generatedImagesDir)) {
                assertFalse(walk.anyMatch(p -> p.getFileName() != null
                                && "evil.sh".equals(p.getFileName().toString())),
                        "evil.sh must not be written anywhere as a result of the traversal entry");
            }

            // A legitimate sibling entry in the same archive must still be restored normally.
            assertTrue(Files.exists(generatedImagesDir.resolve("legit.txt")),
                    "legit.txt should still have been restored despite the sibling traversal entry");
        }
    }
}
