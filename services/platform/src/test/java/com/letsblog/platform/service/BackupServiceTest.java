package com.letsblog.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.platform.config.BackupProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.mockito.Mock;
import org.slf4j.LoggerFactory;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
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

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private CurrentActorService currentActorService;

    private BackupService service;

    @TempDir
    Path generatedImagesDir;

    @BeforeEach
    void setUp() {
        service = new BackupService(buildDefaultProperties(), generatedImagesDir.toString(), ENCRYPTION_KEY,
                adminAuthorizationService, new ObjectMapper(), auditLogService, currentActorService);
    }

    private BackupProperties buildDefaultProperties() {
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
        return properties;
    }

    private byte[] buildArchive(String encryptionKeyHash) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("metadata.json"));
            zip.write(new ObjectMapper().writeValueAsBytes(new BackupService.BackupMetadata(
                    encryptionKeyHash, Instant.now().toString(), MYSQL_SCHEMAS, List.of(POSTGRES_DATABASE), null, null)));
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
                    sha256Hex(ENCRYPTION_KEY), Instant.now().toString(), MYSQL_SCHEMAS, List.of(POSTGRES_DATABASE), null, null)));
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

    @Nested
    @DisplayName("runProcess stderr handling on failure (issue #1203)")
    class RunProcessStderrHandlingTests {

        private static final String STDIN_ENV_VAR = "IRRELEVANT_ENV_VAR";
        private static final String STDIN_ENV_VALUE = "irrelevant";

        /**
         * pg_restoreが読み込めないアーカイブを検知して早期に終了し、標準入力の読み込み側を閉じた
         * 状況を模す: "head -c 5"が5バイトだけ読んで終了し、標準エラーへ本来のエラーメッセージを
         * 出力してから非0で終了する。Java側からstdin全体(head容量よりずっと大きい)を書き込もうと
         * すると、既に読み込み側が閉じているため IOException("Broken pipe") が発生する。
         */
        @Test
        @DisplayName("AC1/AC3: stdin書き込み中にBroken pipeが起きても、握りつぶさずプロセスの標準エラー内容を"
                + "主因として例外メッセージに含める(「Broken pipe」だけにはしない)")
        void includesStderrWhenStdinWriteBreaksDueToEarlyProcessExit() {
            String stderrMessage = "pg_restore: error: unsupported version (1.16) in file header";
            List<String> command = List.of("sh", "-c",
                    "head -c 5 >/dev/null; echo '" + stderrMessage + "' 1>&2; exit 1");
            byte[] largeStdin = new byte[2_000_000];

            BackupException exception = assertThrows(BackupException.class,
                    () -> service.runProcess(command, STDIN_ENV_VAR, STDIN_ENV_VALUE, largeStdin, "pg_restore"));

            assertThat("stderr should be surfaced as the primary reason",
                    exception.getMessage(), containsString(stderrMessage));
            assertThat("raw \"Broken pipe\" alone must not be the reported reason once stderr is available",
                    exception.getMessage(), not(containsString("Broken pipe")));
        }

        /**
         * stdin書き込みが壊れない(=Broken pipeが発生しない)、通常の非0終了のケース。
         * この場合も引き続き標準エラーの内容が例外メッセージに含まれること(既存の挙動の回帰確認)。
         */
        @Test
        @DisplayName("AC3(no regression): stdinの書き込みが壊れない通常の非0終了でも、標準エラーの内容が"
                + "引き続き例外メッセージに含まれる")
        void includesStderrForPlainNonzeroExitWithoutBrokenPipe() {
            String stderrMessage = "mysql: error: some plain failure";
            List<String> command = List.of("sh", "-c",
                    "cat >/dev/null; echo '" + stderrMessage + "' 1>&2; exit 2");
            byte[] stdin = "SELECT 1;".getBytes(StandardCharsets.UTF_8);

            BackupException exception = assertThrows(BackupException.class,
                    () -> service.runProcess(command, STDIN_ENV_VAR, STDIN_ENV_VALUE, stdin, "mysqlによるリストア"));

            assertThat(exception.getMessage(), containsString(stderrMessage));
            assertThat(exception.getMessage(), containsString("exit=2"));
        }

        @Test
        @DisplayName("AC4(no regression): stdinを伴う成功時は例外を投げず標準出力をそのまま返す")
        void returnsStdoutOnSuccessWithStdin() throws Exception {
            List<String> command = List.of("sh", "-c", "cat >/dev/null; printf 'STDOUT_CONTENT'; exit 0");
            byte[] stdin = "dump-content".getBytes(StandardCharsets.UTF_8);

            byte[] result = service.runProcess(command, STDIN_ENV_VAR, STDIN_ENV_VALUE, stdin, "mysqlによるリストア");

            assertEquals("STDOUT_CONTENT", new String(result, StandardCharsets.UTF_8));
        }

        @Test
        @DisplayName("AC4(no regression): stdinを伴わない成功時(ダンプ取得)も例外を投げず標準出力を返す")
        void returnsStdoutOnSuccessWithoutStdin() {
            List<String> command = List.of("sh", "-c", "printf 'DUMP_BYTES'; exit 0");

            byte[] result = service.runProcess(command, STDIN_ENV_VAR, STDIN_ENV_VALUE, null, "pg_dump");

            assertEquals("DUMP_BYTES", new String(result, StandardCharsets.UTF_8));
        }

        /**
         * requirement 1のカバレッジ完全化: stdin書き込みがBroken pipeで失敗したにもかかわらず、
         * プロセス自体はexit=0で終了する稀なケースでも、書き込み失敗を握りつぶさず失敗として扱う
         * ことを検証する(exitValue!=0とstdinWriteFailure!=nullのOR条件のうち、後者のみが
         * trueとなる分岐)。
         */
        @Test
        @DisplayName("stdin書き込みがBroken pipeで失敗した場合、プロセス自体がexit=0でも失敗として扱う")
        void treatsAsFailureWhenStdinWriteBreaksEvenIfProcessExitsZero() {
            List<String> command = List.of("sh", "-c", "head -c 5 >/dev/null; exit 0");
            byte[] largeStdin = new byte[2_000_000];

            BackupException exception = assertThrows(BackupException.class,
                    () -> service.runProcess(command, STDIN_ENV_VAR, STDIN_ENV_VALUE, largeStdin, "pg_restore"));

            assertThat(exception.getMessage(), containsString("exit=0"));
            assertThat("stderr was empty, so the stdin write failure's own message "
                            + "(e.g. \"Broken pipe\") must be the fallback content in the built message",
                    exception.getMessage(), containsString("Broken pipe"));
        }

        /**
         * buildFailureMessageの分岐カバレッジ完全化: 標準エラー出力が全く無く、かつstdin書き込みも
         * 破綻していない(=stdinWriteFailureがnull)、非0終了のケース。この場合はexitのみを
         * 含んだメッセージになる(付加情報を捏造しない)。
         */
        @Test
        @DisplayName("標準エラーが空でstdin書き込みも壊れていない非0終了では、exit番号のみのメッセージになる")
        void nonzeroExitWithNoStderrAndNoStdinFailureYieldsExitOnlyMessage() {
            List<String> command = List.of("sh", "-c", "cat >/dev/null; exit 3");
            byte[] stdin = "SELECT 1;".getBytes(StandardCharsets.UTF_8);

            BackupException exception = assertThrows(BackupException.class,
                    () -> service.runProcess(command, STDIN_ENV_VAR, STDIN_ENV_VALUE, stdin, "mysqlによるリストア"));

            assertThat(exception.getMessage(), containsString("exit=3"));
            assertEquals("mysqlによるリストアが失敗しました(exit=3)", exception.getMessage());
        }
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
                        List.of(POSTGRES_DATABASE), null, null)));
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
    @DisplayName("PostgreSQL dump excludes the volatile jgroups_ping table (issue #1142)")
    class PostgresDumpJgroupsPingExclusionTests {

        /**
         * jgroups_pingはKeycloakのJGroups/Infinispanクラスタ構成員検出用の一時テーブルで、
         * Keycloakが稼働中は継続的に書き込む。pg_restore --cleanは対象テーブルをDROPして
         * CREATE TABLE(制約なし)し、データ投入後にPRIMARY KEY制約を追加する2段構成のため、
         * この間の一瞬の間隙にKeycloak自身の書き込みが入ると、ダンプ内の行と重複して
         * duplicate keyエラーになる(issue #1142の再現条件)。address列は再起動すれば
         * Keycloakが自然に再構築する一時的なクラスタ状態のため、バックアップ/リストアの
         * 対象から丸ごと除外する(pg_dump --exclude-table)ことで、この間隙自体を無くす。
         * pg_restore --clean --if-existsは元々対象に無いテーブルへは何もしないため、
         * リストア側の変更は不要。
         */
        private BackupProperties.Postgres postgresProperties() {
            BackupProperties.Postgres postgres = new BackupProperties.Postgres();
            postgres.setHost("localhost");
            postgres.setPort("5432");
            postgres.setUser("keycloak");
            postgres.setPassword("secret");
            postgres.setDatabase(POSTGRES_DATABASE);
            return postgres;
        }

        @SuppressWarnings("unchecked")
        private List<String> invokeBuildPgDumpCommand(BackupProperties.Postgres postgres) throws Exception {
            Method method = BackupService.class.getDeclaredMethod("buildPgDumpCommand",
                    BackupProperties.Postgres.class);
            method.setAccessible(true);
            return (List<String>) method.invoke(null, postgres);
        }

        @Test
        @DisplayName("pg_dump command excludes public.jgroups_ping to avoid the restore-time "
                + "duplicate key race against Keycloak's own concurrent writes")
        void pgDumpCommandExcludesJgroupsPing() throws Exception {
            List<String> command = invokeBuildPgDumpCommand(postgresProperties());

            assertThat(command, hasItem("--exclude-table=public.jgroups_ping"));
        }

        @Test
        @DisplayName("pg_dump command still targets the configured database and format")
        void pgDumpCommandStillTargetsConfiguredDatabase() throws Exception {
            List<String> command = invokeBuildPgDumpCommand(postgresProperties());

            assertThat(command, hasItem(POSTGRES_DATABASE));
            assertThat(command, hasItem("--format=custom"));
            assertThat(command, not(hasItem("jgroups_ping")));
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
                        List.of(POSTGRES_DATABASE), null, null)));
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

    /**
     * coverage follow-up (issue #1203のカバレッジ不足フォローアップ): runProcess()の
     * catch(IOException | InterruptedException e)ブロック内、
     * 「e instanceof InterruptedException」がtrueとなる分岐(プロセス終了待機中にスレッドが
     * 割り込まれた場合)を検証する。falseの分岐(IOException、例: 実行ファイルが見つからない)は、
     * 他の多くのテスト(実バイナリが存在しない環境で"mysql"/"pg_restore"等を起動しようとするもの)で
     * 既に踏まれている。
     */
    @Nested
    @DisplayName("runProcess interruption branch (coverage follow-up for #1203)")
    class RunProcessInterruptionTests {

        @Test
        @DisplayName("プロセス終了待機中にスレッドが割り込まれた場合、InterruptedExceptionの分岐を通り、"
                + "割り込み状態を再設定した上でBackupExceptionとして失敗を伝える")
        void treatsInterruptionDuringWaitAsFailureAndRestoresInterruptFlag() throws InterruptedException {
            List<String> command = List.of("sh", "-c", "sleep 5; exit 0");
            AtomicReference<Throwable> captured = new AtomicReference<>();
            AtomicBoolean interruptedWhenCaught = new AtomicBoolean(false);

            Thread worker = new Thread(() -> {
                try {
                    service.runProcess(command, "IRRELEVANT_ENV_VAR", "irrelevant", null, "sleep-test");
                } catch (Throwable t) {
                    captured.set(t);
                    interruptedWhenCaught.set(Thread.currentThread().isInterrupted());
                }
            });

            worker.start();
            Thread.sleep(200);
            worker.interrupt();
            worker.join(10_000);

            assertFalse(worker.isAlive(), "worker thread should have finished after being interrupted");
            assertInstanceOf(BackupException.class, captured.get(),
                    "an InterruptedException during waitFor should surface as a BackupException");
            assertTrue(interruptedWhenCaught.get(),
                    "the thread's interrupt status must be restored before the exception propagates");
        }
    }

    @Nested
    @DisplayName("createBackup audit log summary (issue #1246)")
    class CreateBackupAuditTests {

        @Test
        @DisplayName("AC1/AC5: DB_BACKUP_DOWNLOADEDを1件だけ記録し、changesはZIP内容を含まない要約(スキーマ/サイズ/作成日時)")
        void recordsSummaryWithoutArchiveContent() throws Exception {
            BackupService spied = spy(service);
            doReturn("v").when(spied).queryClientVersion(anyString());
            byte[] dump = new byte[200_000];
            java.util.Arrays.fill(dump, (byte) 'Z');
            doReturn(dump).when(spied).runProcess(anyList(), anyString(), anyString(), any(), anyString());
            org.mockito.Mockito.when(currentActorService.getCurrentActorId()).thenReturn(9L);
            org.mockito.Mockito.when(currentActorService.getCurrentActorKeycloakSub()).thenReturn("sub-9");
            org.mockito.Mockito.when(currentActorService.getRemoteIp()).thenReturn("10.0.0.1");
            org.mockito.Mockito.when(currentActorService.getUserAgent()).thenReturn("ua");

            byte[] archive = spied.createBackup();

            org.mockito.ArgumentCaptor<String> changes = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(auditLogService, org.mockito.Mockito.times(1)).log(eq(9L), eq("sub-9"),
                    eq(com.letsblog.platform.domain.AuditLogAction.DB_BACKUP_DOWNLOADED), eq("DATABASE"),
                    eq((Long) null), changes.capture(), eq("10.0.0.1"), eq("ua"));
            com.fasterxml.jackson.databind.JsonNode node = new ObjectMapper().readTree(changes.getValue());
            assertEquals(MYSQL_SCHEMAS.size(), node.get("mysqlSchemas").size());
            assertEquals("lbs_identity", node.get("mysqlSchemas").get(0).asText());
            assertEquals(POSTGRES_DATABASE, node.get("postgresDatabase").asText());
            assertEquals(archive.length, node.get("sizeBytes").asLong());
            assertThat(node.get("createdAt").asText(), notNullValue());
            assertTrue(changes.getValue().length() < 1_000, "summary must be small");
            assertFalse(changes.getValue().contains("Z".repeat(50)));
            assertFalse(changes.getValue().contains("UEsD"));
        }

        @Test
        @DisplayName("監査ログの記録に失敗してもバックアップは返す")
        void auditFailureDoesNotBreakBackup() {
            BackupService spied = spy(service);
            doReturn("v").when(spied).queryClientVersion(anyString());
            doReturn(new byte[]{1}).when(spied).runProcess(anyList(), anyString(), anyString(), any(), anyString());
            org.mockito.Mockito.when(currentActorService.getCurrentActorId())
                    .thenThrow(new IllegalStateException("no actor"));

            byte[] archive = spied.createBackup();

            assertThat(archive.length, greaterThan(0));
            verify(auditLogService, never()).log(any(), any(), any(), anyString(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("管理者でなければバックアップも監査ログも作らない")
        void nonAdminRecordsNothing() {
            doThrow(new ForbiddenException("no")).when(adminAuthorizationService).requireAdmin();

            assertThrows(ForbiddenException.class, () -> service.createBackup());

            verify(auditLogService, never()).log(any(), any(), any(), anyString(), any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("Client version metadata and restore compatibility check (issue #1204)")
    class ClientVersionMetadataTests {

        private BackupService spied;

        @BeforeEach
        void spyService() {
            spied = spy(service);
        }

        private byte[] archiveWithMetadataJson(String metadataJson) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(out)) {
                if (metadataJson != null) {
                    zip.putNextEntry(new ZipEntry("metadata.json"));
                    zip.write(metadataJson.getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
                zip.putNextEntry(new ZipEntry("mysql/lbs_identity.sql"));
                zip.write("SELECT 1;".getBytes());
                zip.closeEntry();
                zip.putNextEntry(new ZipEntry("postgres/" + POSTGRES_DATABASE + ".dump"));
                zip.write(new byte[]{1, 2, 3});
                zip.closeEntry();
            }
            return out.toByteArray();
        }

        private byte[] archiveWithPgDumpVersion(String pgDumpVersion) throws IOException {
            byte[] json = new ObjectMapper().writeValueAsBytes(new BackupService.BackupMetadata(
                    sha256Hex(ENCRYPTION_KEY), Instant.now().toString(), MYSQL_SCHEMAS,
                    List.of(POSTGRES_DATABASE), pgDumpVersion, null));
            return archiveWithMetadataJson(new String(json, StandardCharsets.UTF_8));
        }

        private ListAppender<ILoggingEvent> captureLogs() {
            Logger logger = (Logger) LoggerFactory.getLogger(BackupService.class);
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            logger.addAppender(appender);
            return appender;
        }

        private void detach(ListAppender<ILoggingEvent> appender) {
            ((Logger) LoggerFactory.getLogger(BackupService.class)).detachAppender(appender);
        }

        private void stubRestoreProcesses() {
            doReturn(new byte[0]).when(spied).runProcess(anyList(), anyString(), anyString(), any(), anyString());
        }

        @Test
        @DisplayName("createBackup records the pg_dump and mysqldump versions in metadata.json")
        void createBackupRecordsClientVersions() throws Exception {
            doReturn("pg_dump (PostgreSQL) 15.4").when(spied).queryClientVersion("pg_dump");
            doReturn("mysqldump  Ver 8.0.36 for Linux on x86_64").when(spied).queryClientVersion("mysqldump");
            doReturn(new byte[]{1}).when(spied).runProcess(anyList(), anyString(), anyString(), any(), anyString());

            byte[] archive = spied.createBackup();

            BackupService.BackupMetadata metadata = null;
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if ("metadata.json".equals(entry.getName())) {
                        metadata = new ObjectMapper().readValue(zip.readAllBytes(),
                                BackupService.BackupMetadata.class);
                    }
                }
            }
            assertThat(metadata, notNullValue());
            assertEquals("pg_dump (PostgreSQL) 15.4", metadata.pgDumpVersion());
            assertEquals("mysqldump  Ver 8.0.36 for Linux on x86_64", metadata.mysqldumpVersion());
        }

        @Test
        @DisplayName("queryClientVersion runs '<binary> --version' and returns the trimmed output")
        void queryClientVersionTrimsOutput() {
            doReturn("pg_dump (PostgreSQL) 15.4\n".getBytes(StandardCharsets.UTF_8)).when(spied)
                    .runProcess(eq(List.of("pg_dump", "--version")), anyString(), anyString(), any(), anyString());

            assertEquals("pg_dump (PostgreSQL) 15.4", spied.queryClientVersion("pg_dump"));
        }

        @Test
        @DisplayName("metadata.json written before this change (no version fields) still deserializes, as null")
        void legacyMetadataDeserializesWithNullVersions() throws Exception {
            String legacy = "{\"encryptionKeyHash\":\"h\",\"createdAt\":\"t\","
                    + "\"mysqlSchemas\":[\"a\"],\"postgresDatabases\":[\"b\"]}";

            BackupService.BackupMetadata metadata =
                    new ObjectMapper().readValue(legacy, BackupService.BackupMetadata.class);

            assertEquals(null, metadata.pgDumpVersion());
            assertEquals(null, metadata.mysqldumpVersion());
        }

        @Test
        @DisplayName("restore rejects an archive made by a newer pg_dump than pg_restore, before starting any restore process")
        void rejectsArchiveNewerThanPgRestore() throws Exception {
            doReturn("pg_restore (PostgreSQL) 15.4").when(spied).queryClientVersion("pg_restore");
            byte[] archive = archiveWithPgDumpVersion("pg_dump (PostgreSQL) 18.0");

            BackupException ex = assertThrows(BackupException.class,
                    () -> spied.restoreBackup(new ByteArrayInputStream(archive), true, false));

            assertThat(ex.getMessage(), containsString("pg_dump (PostgreSQL) 18.0"));
            assertThat(ex.getMessage(), containsString("pg_restore (PostgreSQL) 15.4"));
            verify(spied, never()).runProcess(anyList(), anyString(), anyString(), any(), anyString());
        }

        @Test
        @DisplayName("restore proceeds when the archive's pg_dump major equals pg_restore's")
        void acceptsEqualMajor() throws Exception {
            doReturn("pg_restore (PostgreSQL) 15.6").when(spied).queryClientVersion("pg_restore");
            stubRestoreProcesses();
            byte[] archive = archiveWithPgDumpVersion("pg_dump (PostgreSQL) 15.4");

            spied.restoreBackup(new ByteArrayInputStream(archive), true, false);

            verify(spied).runProcess(anyList(), eq("PGPASSWORD"), anyString(), any(), eq("pg_restore"));
        }

        @Test
        @DisplayName("restore proceeds when the archive was made by an older pg_dump")
        void acceptsOlderMajor() throws Exception {
            doReturn("pg_restore (PostgreSQL) 15.6").when(spied).queryClientVersion("pg_restore");
            stubRestoreProcesses();
            byte[] archive = archiveWithPgDumpVersion("pg_dump (PostgreSQL) 13.2");

            spied.restoreBackup(new ByteArrayInputStream(archive), true, false);

            verify(spied).runProcess(anyList(), eq("PGPASSWORD"), anyString(), any(), eq("pg_restore"));
        }

        @Test
        @DisplayName("legacy archive whose metadata has no version warns that the client version is unknown, and restores")
        void legacyMetadataWarnsAndProceeds() throws Exception {
            stubRestoreProcesses();
            String legacy = "{\"encryptionKeyHash\":\"" + sha256Hex(ENCRYPTION_KEY) + "\",\"createdAt\":\"t\","
                    + "\"mysqlSchemas\":[\"lbs_identity\"],\"postgresDatabases\":[\"keycloak\"]}";
            byte[] archive = archiveWithMetadataJson(legacy);
            ListAppender<ILoggingEvent> appender = captureLogs();
            try {
                spied.restoreBackup(new ByteArrayInputStream(archive), true, false);
            } finally {
                detach(appender);
            }

            assertTrue(appender.list.stream().anyMatch(e -> e.getLevel() == Level.WARN
                    && e.getFormattedMessage().contains("判別できません")));
            verify(spied, never()).queryClientVersion(anyString());
            verify(spied).runProcess(anyList(), eq("PGPASSWORD"), anyString(), any(), eq("pg_restore"));
        }

        @Test
        @DisplayName("archive without metadata.json warns that the client version is unknown, and restores")
        void missingMetadataWarnsAndProceeds() throws Exception {
            stubRestoreProcesses();
            byte[] archive = archiveWithMetadataJson(null);
            ListAppender<ILoggingEvent> appender = captureLogs();
            try {
                spied.restoreBackup(new ByteArrayInputStream(archive), true, false);
            } finally {
                detach(appender);
            }

            assertTrue(appender.list.stream().anyMatch(e -> e.getLevel() == Level.WARN
                    && e.getFormattedMessage().contains("判別できません")));
        }

        @Test
        @DisplayName("an unparsable recorded pg_dump version is treated as undeterminable: warn and restore")
        void unparsableRecordedVersionWarnsAndProceeds() throws Exception {
            stubRestoreProcesses();
            byte[] archive = archiveWithPgDumpVersion("garbage");
            ListAppender<ILoggingEvent> appender = captureLogs();
            try {
                spied.restoreBackup(new ByteArrayInputStream(archive), true, false);
            } finally {
                detach(appender);
            }
            assertTrue(appender.list.stream().anyMatch(e -> e.getLevel() == Level.WARN
                    && e.getFormattedMessage().contains("判別できません")));
            verify(spied, never()).queryClientVersion(anyString());
        }

        @Test
        @DisplayName("an unparsable current pg_restore version is treated as undeterminable: warn and restore")
        void unparsableCurrentVersionWarnsAndProceeds() throws Exception {
            doReturn("garbage").when(spied).queryClientVersion("pg_restore");
            stubRestoreProcesses();
            byte[] archive = archiveWithPgDumpVersion("pg_dump (PostgreSQL) 18.0");
            ListAppender<ILoggingEvent> appender = captureLogs();
            try {
                spied.restoreBackup(new ByteArrayInputStream(archive), true, false);
            } finally {
                detach(appender);
            }
            assertTrue(appender.list.stream().anyMatch(e -> e.getLevel() == Level.WARN
                    && e.getFormattedMessage().contains("判別できません")));
        }

        @Test
        @DisplayName("an archive with a recorded version but no postgres dump does not query pg_restore")
        void noPostgresDumpSkipsComparison() throws Exception {
            stubRestoreProcesses();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(out)) {
                zip.putNextEntry(new ZipEntry("metadata.json"));
                zip.write(new ObjectMapper().writeValueAsBytes(new BackupService.BackupMetadata(
                        sha256Hex(ENCRYPTION_KEY), "t", MYSQL_SCHEMAS, List.of(POSTGRES_DATABASE),
                        "pg_dump (PostgreSQL) 18.0", null)));
                zip.closeEntry();
                zip.putNextEntry(new ZipEntry("mysql/lbs_identity.sql"));
                zip.write("SELECT 1;".getBytes());
                zip.closeEntry();
            }

            spied.restoreBackup(new ByteArrayInputStream(out.toByteArray()), true, false);

            verify(spied, never()).queryClientVersion(anyString());
        }
    }
}
