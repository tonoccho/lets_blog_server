package com.letsblog.platform.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.common.messaging.AuditLogMessage;
import com.letsblog.platform.config.BackupProperties;
import com.letsblog.platform.keycloak.KeycloakAdminClient;
import com.letsblog.platform.testsupport.FakeBin;
import com.letsblog.platform.testsupport.FakeHttpServer;
import com.letsblog.platform.testsupport.KeycloakFakes;
import com.letsblog.platform.testsupport.PlatformWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * BackupService unit tests covering authorization, validation, and archive structure
 * (legacy-apiのBackupServiceTestと同じ観点を、全MySQLスキーマ+Keycloak PostgreSQL対応後の
 * アーカイブ構成に対して踏襲する。issue #694)。
 *
 * <p>issue #1825: 自前のクラス(AdminAuthorizationService / AuditLogService / CurrentActorService /
 * KeycloakAdminClient / BackupService自身)はモックもスパイもしない。差し替える外部との通信は次だけ。
 * <ul>
 *   <li>外部プロセス(mysqldump / mysql / pg_dump / pg_restore): {@code src/test/fake-bin}の代役の実行ファイルを
 *       PATHの先頭に置く。{@code BackupService#runProcess}は実物のまま{@code ProcessBuilder}で起動する({@link FakeBin})。</li>
 *   <li>HTTP(identity-service・Keycloak): サーバー側のフェイク({@code FakeHttpServer})。</li>
 *   <li>MQ: {@code RabbitTemplate}のMockito mock(実物の{@code AuditLogService}へ渡す)。</li>
 * </ul>
 */
class BackupServiceTest {

    private static final String ENCRYPTION_KEY = "test-encryption-key";
    private static final List<String> MYSQL_SCHEMAS = List.of("lbs_identity", "lbs_media", "lets_blog");
    private static final String POSTGRES_DATABASE = "keycloak";

    private PlatformWorld world;
    private KeycloakAdminClient keycloakAdminClient;
    private BackupService service;

    @TempDir
    Path generatedImagesDir;

    @BeforeEach
    void setUp() {
        FakeBin.reset();
        world = new PlatformWorld().actingAsAdmin(1L);
        keycloakAdminClient = KeycloakFakes.client(world.http);
        service = new BackupService(buildDefaultProperties(), generatedImagesDir.toString(), ENCRYPTION_KEY,
                world.adminAuthorizationService, new ObjectMapper(), world.auditLogService, world.currentActorService,
                keycloakAdminClient);
    }

    @AfterEach
    void tearDown() {
        world.close();
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
            world.actingAsUser(2L);

            assertThrows(ForbiddenException.class, () -> service.createBackup());
            assertTrue(FakeBin.calls().isEmpty(), "権限が無ければ外部プロセスを起動しない");
        }
    }

    @Nested
    @DisplayName("Backup Restore Authorization")
    class RestoreBackupAuthorizationTests {

        @Test
        @DisplayName("restoreBackup requires admin privileges")
        void requiresAdminPrivileges() {
            world.actingAsUser(2L);

            assertThrows(ForbiddenException.class,
                    () -> service.restoreBackup(new ByteArrayInputStream(new byte[0]), true, false));
        }

        @Test
        @DisplayName("restoreBackup validates admin authorization first")
        void validatesAuthorizationBeforeConfirmCheck() {
            world.actingAsUser(2L);

            assertThrows(ForbiddenException.class,
                    () -> service.restoreBackup(new ByteArrayInputStream(new byte[0]), false, false));
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
            assertThat(exception.getMessage(), containsString("破壊的操作"));
        }
    }

    @Nested
    @DisplayName("Restore archive validation")
    class RestoreArchiveValidationTests {

        @Test
        @DisplayName("an archive with no MySQL/PostgreSQL dump is rejected")
        void rejectsArchiveWithoutDumps() throws Exception {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(out)) {
                zip.putNextEntry(new ZipEntry("generated-images/a.png"));
                zip.write(new byte[]{1});
                zip.closeEntry();
            }

            BackupException ex = assertThrows(BackupException.class,
                    () -> service.restoreBackup(new ByteArrayInputStream(out.toByteArray()), true, false));

            assertThat(ex.getMessage(), containsString("ダンプが含まれていません"));
        }

        @Test
        @DisplayName("a broken ZIP stream is reported as an archive read failure")
        void brokenArchiveIsReported() {
            java.io.InputStream broken = new java.io.InputStream() {
                @Override
                public int read() throws IOException {
                    throw new IOException("connection reset while uploading");
                }
            };

            BackupException ex = assertThrows(BackupException.class,
                    () -> service.restoreBackup(broken, true, false));

            assertThat(ex.getMessage(), containsString("読み込みに失敗"));
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
            assertTrue(FakeBin.invocations("mysql").isEmpty(), "鍵が違えば何も復元しない");
        }

        @Test
        @DisplayName("restore proceeds on key mismatch when the operator acknowledges it")
        void proceedsOnKeyMismatchWhenAcknowledged() throws Exception {
            byte[] archive = buildArchive(sha256Hex("different-key"));

            service.restoreBackup(new ByteArrayInputStream(archive), true, true);

            assertEquals(MYSQL_SCHEMAS.size(), FakeBin.invocations("mysql").size());
        }

        @Test
        @DisplayName("restore requires confirmation and checks key mismatch after confirmation")
        void requiresConfirmationBeforeKeyCheck() throws Exception {
            byte[] archive = buildArchive(sha256Hex("different-key"));

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.restoreBackup(new ByteArrayInputStream(archive), false, false));

            assertThat(ex.getMessage(), containsString("confirm=true"));
        }
    }

    @Nested
    @DisplayName("Backup Archive Structure (createBackup against the stand-in dump commands)")
    class BackupArchiveStructureTests {

        private java.util.Map<String, byte[]> entriesOf(byte[] archive) throws IOException {
            java.util.Map<String, byte[]> entries = new java.util.LinkedHashMap<>();
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    entries.put(entry.getName(), zip.readAllBytes());
                }
            }
            return entries;
        }

        @Test
        @DisplayName("archive contains a metadata.json entry and one sql dump entry per configured schema")
        void archiveContainsRequiredEntries() throws Exception {
            java.util.Map<String, byte[]> entries = entriesOf(service.createBackup());

            assertTrue(entries.containsKey("metadata.json"), "metadata.json entry missing");
            assertTrue(entries.containsKey("postgres/" + POSTGRES_DATABASE + ".dump"), "postgres dump entry missing");
            for (String schema : MYSQL_SCHEMAS) {
                assertEquals("-- mysqldump " + schema + "\n",
                        new String(entries.get("mysql/" + schema + ".sql"), StandardCharsets.UTF_8));
            }
        }

        @Test
        @DisplayName("archive includes generated images when present, and not when the directory is absent")
        void archiveIncludesGeneratedImages() throws Exception {
            Files.createDirectories(generatedImagesDir.resolve("subdir"));
            Files.write(generatedImagesDir.resolve("test-image.jpg"), new byte[]{(byte) 0xFF, (byte) 0xD8});
            Files.write(generatedImagesDir.resolve("subdir/another.png"), new byte[]{(byte) 0x89, 0x50});

            java.util.Map<String, byte[]> entries = entriesOf(service.createBackup());

            assertTrue(entries.containsKey("generated-images/test-image.jpg"), "test-image.jpg missing");
            assertTrue(entries.containsKey("generated-images/subdir/another.png"), "subdir/another.png missing");

            BackupService absent = new BackupService(buildDefaultProperties(),
                    generatedImagesDir.resolve("no-such-dir").toString(), ENCRYPTION_KEY,
                    world.adminAuthorizationService, new ObjectMapper(), world.auditLogService,
                    world.currentActorService, keycloakAdminClient);
            assertTrue(entriesOf(absent.createBackup()).keySet().stream()
                    .noneMatch(name -> name.startsWith("generated-images/")));
        }

        @Test
        @DisplayName("metadata contains encryptionKeyHash, createdAt, mysqlSchemas, postgresDatabases and client versions")
        void metadataContainsRequiredFields() throws Exception {
            FakeBin.version("pg_dump", "pg_dump (PostgreSQL) 15.4");
            FakeBin.version("mysqldump", "mysqldump  Ver 8.0.36 for Linux on x86_64");

            byte[] metadataJson = entriesOf(service.createBackup()).get("metadata.json");
            BackupService.BackupMetadata metadata =
                    new ObjectMapper().readValue(metadataJson, BackupService.BackupMetadata.class);

            assertEquals(sha256Hex(ENCRYPTION_KEY), metadata.encryptionKeyHash());
            assertThat("createdAt", metadata.createdAt(), notNullValue());
            assertEquals(MYSQL_SCHEMAS, metadata.mysqlSchemas());
            assertEquals(List.of(POSTGRES_DATABASE), metadata.postgresDatabases());
            assertEquals("pg_dump (PostgreSQL) 15.4", metadata.pgDumpVersion());
            assertEquals("mysqldump  Ver 8.0.36 for Linux on x86_64", metadata.mysqldumpVersion());
        }

        @Test
        @DisplayName("a failing dump command fails the whole backup with the command's stderr")
        void failingDumpFailsBackup() {
            FakeBin.fail("mysqldump", "mysqldump: Got error: 1045: Access denied");

            BackupException ex = assertThrows(BackupException.class, () -> service.createBackup());

            assertThat(ex.getMessage(), containsString("Access denied"));
        }

        @Test
        @DisplayName("an image file that cannot be read fails the whole backup as BackupException")
        void unreadableImageFailsBackup() throws Exception {
            // 通常ファイルとして列挙されるが、読もうとするとI/Oエラーになるファイル(/proc/self/mem は先頭を読めない)を
            // 生成画像のディレクトリに置く。権限に頼らないので、rootで実行しても同じ結果になる。
            Files.createSymbolicLink(generatedImagesDir.resolve("unreadable.png"), Path.of("/proc/self/mem"));

            BackupException ex = assertThrows(BackupException.class, () -> service.createBackup());

            assertThat(ex.getMessage(), containsString("バックアップアーカイブの作成に失敗"));
        }
    }

    @Nested
    @DisplayName("Dump and restore commands")
    class CommandTests {

        @Test
        @DisplayName("mysqldump is run per schema with the backup user, and pg_dump excludes public.jgroups_ping "
                + "(issue #1142) while still targeting the configured database and format")
        void dumpCommands() {
            service.createBackup();

            List<List<String>> mysqldumps = FakeBin.invocations("mysqldump");
            assertEquals(MYSQL_SCHEMAS.size(), mysqldumps.size());
            assertThat(mysqldumps.get(0), hasItem("--user=lbs_backup"));
            assertThat(mysqldumps.get(0), hasItem("--no-tablespaces"));
            assertEquals("lbs_identity", mysqldumps.get(0).get(mysqldumps.get(0).size() - 1));

            List<String> pgDump = FakeBin.invocations("pg_dump").get(0);
            assertThat(pgDump, hasItem("--exclude-table=public.jgroups_ping"));
            assertThat(pgDump, hasItem(POSTGRES_DATABASE));
            assertThat(pgDump, hasItem("--format=custom"));
            assertThat(pgDump, not(hasItem("jgroups_ping")));
        }

        @Test
        @DisplayName("restore feeds each dump to mysql / pg_restore on stdin and clears the Keycloak caches")
        void restoreCommands() throws Exception {
            service.restoreBackup(new ByteArrayInputStream(buildArchive(sha256Hex(ENCRYPTION_KEY))), true, false);

            assertEquals(MYSQL_SCHEMAS.size(), FakeBin.invocations("mysql").size());
            assertEquals(List.of("SELECT 1;", "SELECT 1;", "SELECT 1;"), FakeBin.stdins("mysql"));
            List<String> pgRestore = FakeBin.invocations("pg_restore").get(0);
            assertThat(pgRestore, hasItem("--clean"));
            assertThat(pgRestore, hasItem("--dbname=" + POSTGRES_DATABASE));
            assertEquals(1, FakeBin.stdins("pg_restore").size());
            assertEquals(1, world.http.requests("POST", "/admin/clear-user-cache").size());
        }

        @Test
        @DisplayName("a failing restore command fails the restore")
        void failingRestoreCommand() throws Exception {
            FakeBin.fail("mysql", "ERROR 1045 (28000): Access denied");

            BackupException ex = assertThrows(BackupException.class, () -> service.restoreBackup(
                    new ByteArrayInputStream(buildArchive(sha256Hex(ENCRYPTION_KEY))), true, false));

            assertThat(ex.getMessage(), containsString("Access denied"));
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

            assertTrue(FakeBin.calls().isEmpty(), "外部プロセスは一度も起動されない");
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
            byte[] dump = new byte[200_000];
            java.util.Arrays.fill(dump, (byte) 'Z');
            FakeBin.output("mysqldump", new String(dump, StandardCharsets.UTF_8));
            world.signedInAs("sub-9");
            world.request.setRemoteAddr("10.0.0.1");
            world.request.addHeader("User-Agent", "ua");

            byte[] archive = service.createBackup();

            ArgumentCaptor<Object> message = ArgumentCaptor.forClass(Object.class);
            verify(world.rabbitTemplate, times(1)).convertAndSend(anyString(), anyString(), message.capture());
            AuditLogMessage audit = (AuditLogMessage) message.getValue();
            assertEquals(1L, audit.userId());
            assertEquals("sub-9", audit.actorKeycloakSub());
            assertEquals("DB_BACKUP_DOWNLOADED", audit.action());
            assertEquals("DATABASE", audit.resourceType());
            assertEquals("10.0.0.1", audit.remoteIp());
            assertEquals("ua", audit.userAgent());
            com.fasterxml.jackson.databind.JsonNode node = new ObjectMapper().readTree(audit.changes());
            assertEquals(MYSQL_SCHEMAS.size(), node.get("mysqlSchemas").size());
            assertEquals("lbs_identity", node.get("mysqlSchemas").get(0).asText());
            assertEquals(POSTGRES_DATABASE, node.get("postgresDatabase").asText());
            assertEquals(archive.length, node.get("sizeBytes").asLong());
            assertThat(node.get("createdAt").asText(), notNullValue());
            assertTrue(audit.changes().length() < 1_000, "summary must be small");
            assertFalse(audit.changes().contains("Z".repeat(50)));
            assertFalse(audit.changes().contains("UEsD"));
        }

        @Test
        @DisplayName("監査ログの記録に失敗してもバックアップは返す")
        void auditFailureDoesNotBreakBackup() {
            doThrow(new IllegalStateException("mq down")).when(world.rabbitTemplate)
                    .convertAndSend(anyString(), anyString(), any(Object.class));

            byte[] archive = service.createBackup();

            assertThat(archive.length, greaterThan(0));
        }

        @Test
        @DisplayName("管理者でなければバックアップも監査ログも作らない")
        void nonAdminRecordsNothing() {
            world.actingAsUser(2L);

            assertThrows(ForbiddenException.class, () -> service.createBackup());

            verify(world.rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
        }
    }

    @Nested
    @DisplayName("Keycloak cache invalidation after restore (issue #1590)")
    class KeycloakCacheInvalidationTests {

        private byte[] archive(boolean withPostgres) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(out)) {
                zip.putNextEntry(new ZipEntry("metadata.json"));
                zip.write(new ObjectMapper().writeValueAsBytes(new BackupService.BackupMetadata(
                        sha256Hex(ENCRYPTION_KEY), Instant.now().toString(), MYSQL_SCHEMAS,
                        List.of(POSTGRES_DATABASE), null, null)));
                zip.closeEntry();
                zip.putNextEntry(new ZipEntry("mysql/lbs_identity.sql"));
                zip.write("SELECT 1;".getBytes());
                zip.closeEntry();
                if (withPostgres) {
                    zip.putNextEntry(new ZipEntry("postgres/" + POSTGRES_DATABASE + ".dump"));
                    zip.write(new byte[]{1, 2, 3});
                    zip.closeEntry();
                }
            }
            return out.toByteArray();
        }

        @Test
        @DisplayName("clears Keycloak caches after the PostgreSQL restore, so stale users are not served")
        void clearsCachesAfterPostgresRestore() throws Exception {
            service.restoreBackup(new ByteArrayInputStream(archive(true)), true, true);

            assertEquals(1, FakeBin.invocations("pg_restore").size());
            assertEquals(List.of("/admin/clear-user-cache", "/admin/clear-realm-cache", "/admin/clear-keys-cache"),
                    world.http.requests("POST", "/admin/clear-.*").stream()
                            .map(FakeHttpServer.Request::path).toList());
        }

        @Test
        @DisplayName("does not touch Keycloak when the archive has no PostgreSQL dump")
        void skipsWhenNoPostgresDump() throws Exception {
            service.restoreBackup(new ByteArrayInputStream(archive(false)), true, true);

            assertTrue(world.http.requests("POST", "/admin/clear-.*").isEmpty());
            assertTrue(world.http.requests("POST", "/token").isEmpty());
        }

        @Test
        @DisplayName("a cache-invalidation failure is logged as an error and does not fail the completed restore")
        void cacheInvalidationFailureDoesNotFailRestore() throws Exception {
            world.http.status("POST", "/admin/clear-user-cache", 500, "{}");
            Logger logger = (Logger) LoggerFactory.getLogger(BackupService.class);
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            logger.addAppender(appender);
            try {
                service.restoreBackup(new ByteArrayInputStream(archive(true)), true, true);
            } finally {
                logger.detachAppender(appender);
            }

            assertTrue(appender.list.stream().anyMatch(e -> e.getLevel() == Level.ERROR
                    && e.getFormattedMessage().contains("Keycloak")));
        }
    }

    @Nested
    @DisplayName("Client version metadata and restore compatibility check (issue #1204)")
    class ClientVersionMetadataTests {

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

        private boolean versionQueried(String binary) {
            return FakeBin.calls().stream().anyMatch(c -> c.equals(List.of(binary, "--version")));
        }

        @Test
        @DisplayName("queryClientVersion runs '<binary> --version' and returns the trimmed output")
        void queryClientVersionTrimsOutput() {
            FakeBin.version("pg_dump", "pg_dump (PostgreSQL) 15.4");

            assertEquals("pg_dump (PostgreSQL) 15.4", service.queryClientVersion("pg_dump"));
            assertEquals(List.of(List.of("pg_dump", "--version")), FakeBin.calls());
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
            FakeBin.version("pg_restore", "pg_restore (PostgreSQL) 15.4");
            byte[] archive = archiveWithPgDumpVersion("pg_dump (PostgreSQL) 18.0");

            BackupException ex = assertThrows(BackupException.class,
                    () -> service.restoreBackup(new ByteArrayInputStream(archive), true, false));

            assertThat(ex.getMessage(), containsString("pg_dump (PostgreSQL) 18.0"));
            assertThat(ex.getMessage(), containsString("pg_restore (PostgreSQL) 15.4"));
            assertTrue(FakeBin.invocations("pg_restore").isEmpty());
            assertTrue(FakeBin.invocations("mysql").isEmpty());
        }

        @Test
        @DisplayName("restore proceeds when the archive's pg_dump major equals pg_restore's")
        void acceptsEqualMajor() throws Exception {
            FakeBin.version("pg_restore", "pg_restore (PostgreSQL) 15.6");
            byte[] archive = archiveWithPgDumpVersion("pg_dump (PostgreSQL) 15.4");

            service.restoreBackup(new ByteArrayInputStream(archive), true, false);

            assertEquals(1, FakeBin.invocations("pg_restore").size());
        }

        @Test
        @DisplayName("restore proceeds when the archive was made by an older pg_dump")
        void acceptsOlderMajor() throws Exception {
            FakeBin.version("pg_restore", "pg_restore (PostgreSQL) 15.6");
            byte[] archive = archiveWithPgDumpVersion("pg_dump (PostgreSQL) 13.2");

            service.restoreBackup(new ByteArrayInputStream(archive), true, false);

            assertEquals(1, FakeBin.invocations("pg_restore").size());
        }

        @Test
        @DisplayName("legacy archive whose metadata has no version warns that the client version is unknown, and restores")
        void legacyMetadataWarnsAndProceeds() throws Exception {
            String legacy = "{\"encryptionKeyHash\":\"" + sha256Hex(ENCRYPTION_KEY) + "\",\"createdAt\":\"t\","
                    + "\"mysqlSchemas\":[\"lbs_identity\"],\"postgresDatabases\":[\"keycloak\"]}";
            byte[] archive = archiveWithMetadataJson(legacy);
            ListAppender<ILoggingEvent> appender = captureLogs();
            try {
                service.restoreBackup(new ByteArrayInputStream(archive), true, false);
            } finally {
                detach(appender);
            }

            assertTrue(appender.list.stream().anyMatch(e -> e.getLevel() == Level.WARN
                    && e.getFormattedMessage().contains("判別できません")));
            assertFalse(versionQueried("pg_restore"));
            assertEquals(1, FakeBin.invocations("pg_restore").size());
        }

        @Test
        @DisplayName("archive without metadata.json warns that the client version is unknown, and restores")
        void missingMetadataWarnsAndProceeds() throws Exception {
            byte[] archive = archiveWithMetadataJson(null);
            ListAppender<ILoggingEvent> appender = captureLogs();
            try {
                service.restoreBackup(new ByteArrayInputStream(archive), true, false);
            } finally {
                detach(appender);
            }

            assertTrue(appender.list.stream().anyMatch(e -> e.getLevel() == Level.WARN
                    && e.getFormattedMessage().contains("判別できません")));
            assertEquals(1, FakeBin.invocations("pg_restore").size());
        }

        @Test
        @DisplayName("an unparsable recorded pg_dump version is treated as undeterminable: warn and restore")
        void unparsableRecordedVersionWarnsAndProceeds() throws Exception {
            byte[] archive = archiveWithPgDumpVersion("garbage");
            ListAppender<ILoggingEvent> appender = captureLogs();
            try {
                service.restoreBackup(new ByteArrayInputStream(archive), true, false);
            } finally {
                detach(appender);
            }
            assertTrue(appender.list.stream().anyMatch(e -> e.getLevel() == Level.WARN
                    && e.getFormattedMessage().contains("判別できません")));
            assertFalse(versionQueried("pg_restore"));
        }

        @Test
        @DisplayName("an unparsable current pg_restore version is treated as undeterminable: warn and restore")
        void unparsableCurrentVersionWarnsAndProceeds() throws Exception {
            FakeBin.version("pg_restore", "garbage");
            byte[] archive = archiveWithPgDumpVersion("pg_dump (PostgreSQL) 18.0");
            ListAppender<ILoggingEvent> appender = captureLogs();
            try {
                service.restoreBackup(new ByteArrayInputStream(archive), true, false);
            } finally {
                detach(appender);
            }
            assertTrue(appender.list.stream().anyMatch(e -> e.getLevel() == Level.WARN
                    && e.getFormattedMessage().contains("判別できません")));
            assertEquals(1, FakeBin.invocations("pg_restore").size());
        }

        @Test
        @DisplayName("an archive with a recorded version but no postgres dump does not query pg_restore")
        void noPostgresDumpSkipsComparison() throws Exception {
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

            service.restoreBackup(new ByteArrayInputStream(out.toByteArray()), true, false);

            assertFalse(versionQueried("pg_restore"));
        }
    }

    // ---- issue #1732: 標準出力/標準エラーを読む裏のスレッドも、呼び出し元の処理IDを引き継ぐ ----

    @org.junit.jupiter.api.Test
    void readInBackground_裏のスレッドは呼び出し元の処理IDを引き継ぐ() throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> seen = new java.util.concurrent.atomic.AtomicReference<>();
        java.io.InputStream source = new java.io.InputStream() {
            @Override
            public int read() {
                seen.set(MDC.get("correlationId"));
                return -1;
            }
        };
        MDC.put("correlationId", "cid-backup");
        try {
            Thread reader = service.readInBackground(source, new ByteArrayOutputStream());
            reader.join(5_000);
        } finally {
            MDC.clear();
        }

        org.junit.jupiter.api.Assertions.assertEquals("cid-backup", seen.get());
    }

    @Nested
    @DisplayName("Restore archive entry classification (issue #1825)")
    class RestoreEntryClassificationTests {

        private byte[] archiveOf(String... entryNames) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(out)) {
                for (String name : entryNames) {
                    zip.putNextEntry(new ZipEntry(name));
                    if (!name.endsWith("/")) {
                        zip.write(("content-of-" + name).getBytes(StandardCharsets.UTF_8));
                    }
                    zip.closeEntry();
                }
            }
            return out.toByteArray();
        }

        @Test
        @DisplayName("PostgreSQLのダンプだけのアーカイブも復元できる(MySQLのダンプが無くてもよい)")
        void postgresOnlyArchiveIsRestored() throws Exception {
            service.restoreBackup(new ByteArrayInputStream(archiveOf("postgres/keycloak.dump")), true, false);

            assertEquals(1, FakeBin.invocations("pg_restore").size());
            assertTrue(FakeBin.invocations("mysql").isEmpty());
        }

        @Test
        @DisplayName("接頭辞だけ・接尾辞だけが合うエントリ、ディレクトリのエントリ、無関係なエントリはダンプとして扱わない")
        void unrelatedEntriesAreIgnored() throws Exception {
            byte[] archive = archiveOf(
                    "mysql/README.txt", "postgres/notes.txt", "other-mysql/x.sql", "other-postgres/x.dump",
                    "generated-images/subdir/", "stray.txt", "lbs_identity.sql",
                    "mysql/lbs_identity.sql");

            service.restoreBackup(new ByteArrayInputStream(archive), true, false);

            // ダンプとして扱われたのは mysql/lbs_identity.sql だけ。
            assertEquals(List.of("content-of-mysql/lbs_identity.sql"), FakeBin.stdins("mysql"));
            assertTrue(FakeBin.invocations("pg_restore").isEmpty());
            assertFalse(Files.exists(generatedImagesDir.resolve("subdir/")) && Files.isRegularFile(generatedImagesDir.resolve("subdir")));
        }

        @Test
        @DisplayName("実行ファイルを起動できなければ、実行失敗のBackupExceptionにする(割り込み状態は立てない)")
        void unlaunchableCommandIsReported() {
            BackupException ex = assertThrows(BackupException.class, () -> service.runProcess(
                    List.of("/nonexistent/lbs-no-such-binary"), "X", "y", null, "missing-binary"));

            assertThat(ex.getMessage(), containsString("missing-binaryの実行に失敗しました"));
            assertFalse(Thread.currentThread().isInterrupted());
        }
    }
}
