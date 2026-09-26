package com.letsblog.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.platform.aop.AuditLog;
import com.letsblog.platform.config.BackupProperties;
import com.letsblog.platform.domain.AuditLogAction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Let's Blog全体のデータ(全サービスのMySQLスキーマ + Keycloakの認証情報を保持するPostgreSQL +
 * 生成画像ファイル)をバックアップ/リストアする(issue #694、C10-2)。managed WordPressサイト個別の
 * DB/ファイルは対象外(各サイト自体のバックアップ手段に委ねる)。
 *
 * <p>legacy-apiのBackupService(単一のMySQLスキーマのみが対象だった)をplatform-serviceへ移設し、
 * 対象を全サービスのMySQLスキーマ(#570のスキーマ分離により分かれている)とKeycloak PostgreSQL
 * (認証基盤、ADR-0002)へ拡張したもの。各サービス専用のDBユーザーでは他スキーマにアクセスできない
 * ため、{@link BackupProperties}が保持するバックアップ専用の横断的なMySQL認証情報
 * ({@code lbs_backup}、infra/mysql/init/01-create-service-schemas.sh参照)を使う。KeycloakのPostgreSQLは
 * 元々そのDB専用の{@code keycloak}ユーザーしか存在しないため、その認証情報をそのまま流用する。
 *
 * <p>バックアップはZIPアーカイブとして、metadata.json・スキーマ/DBごとのSQLダンプ
 * (mysql/&lt;schema&gt;.sql、postgres/&lt;database&gt;.dump)・generated-images/ を1つにまとめる。
 * metadata.jsonにはバックアップ作成時点のAPP_ENCRYPTION_KEYのハッシュ値と、含まれるスキーマ/DBの
 * 一覧を記録し、リストア時に現在の環境のキーと一致するか検証する(サイト認証情報等はこのキーで
 * 暗号化されているため、異なるキーの環境へリストアすると復号できなくなり実質的にデータが壊れるのを
 * 防ぐため)。バックアップ/リストアはいずれもadmin限定の破壊的操作になり得るため、
 * AdminAuthorizationServiceで権限を確認した上で実行し、監査ログに記録する。
 */
@Service
@Slf4j
public class BackupService {

    private static final int TIMEOUT_SECONDS = 300;
    private static final String GENERATED_IMAGES_ENTRY_PREFIX = "generated-images/";
    private static final String MYSQL_ENTRY_PREFIX = "mysql/";
    private static final String MYSQL_ENTRY_SUFFIX = ".sql";
    private static final String POSTGRES_ENTRY_PREFIX = "postgres/";
    private static final String POSTGRES_ENTRY_SUFFIX = ".dump";
    private static final Pattern POSTGRES_MAJOR_VERSION = Pattern.compile("PostgreSQL\\)\\s+(\\d+)");

    private final BackupProperties backupProperties;
    private final Path generatedImagesDir;
    private final String encryptionKey;
    private final AdminAuthorizationService adminAuthorizationService;
    private final ObjectMapper objectMapper;
    private final AuditLogService auditLogService;
    private final CurrentActorService currentActorService;

    public BackupService(
            BackupProperties backupProperties,
            @Value("${app.generated-images-storage-path}") String generatedImagesStoragePath,
            @Value("${app.encryption-key}") String encryptionKey,
            AdminAuthorizationService adminAuthorizationService,
            ObjectMapper objectMapper,
            AuditLogService auditLogService,
            CurrentActorService currentActorService) {
        this.backupProperties = backupProperties;
        this.generatedImagesDir = Path.of(generatedImagesStoragePath);
        this.encryptionKey = encryptionKey;
        this.adminAuthorizationService = adminAuthorizationService;
        this.objectMapper = objectMapper;
        this.auditLogService = auditLogService;
        this.currentActorService = currentActorService;
    }

    public record BackupMetadata(
            String encryptionKeyHash,
            String createdAt,
            List<String> mysqlSchemas,
            List<String> postgresDatabases,
            String pgDumpVersion,
            String mysqldumpVersion) {
    }

    /**
     * バックアップZIPを作成する。監査ログは{@code @AuditLog}(戻り値をそのまま記録する)ではなく、
     * 本体を含まない要約(対象スキーマ/サイズ/作成日時)を明示的に記録する(issue #1246)。
     */
    public byte[] createBackup() {
        adminAuthorizationService.requireAdmin();

        List<String> mysqlSchemas = backupProperties.getMysql().getSchemas();
        String postgresDatabase = backupProperties.getPostgres().getDatabase();
        BackupMetadata metadata = new BackupMetadata(
                sha256Hex(encryptionKey), Instant.now().toString(), mysqlSchemas, List.of(postgresDatabase),
                queryClientVersion("pg_dump"), queryClientVersion("mysqldump"));

        ByteArrayOutputStream zipBytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(zipBytes)) {
            writeZipEntry(zip, "metadata.json", objectMapper.writeValueAsBytes(metadata));
            for (String schema : mysqlSchemas) {
                writeZipEntry(zip, MYSQL_ENTRY_PREFIX + schema + MYSQL_ENTRY_SUFFIX, dumpMysqlSchema(schema));
            }
            writeZipEntry(zip, POSTGRES_ENTRY_PREFIX + postgresDatabase + POSTGRES_ENTRY_SUFFIX,
                    dumpPostgresDatabase());
            writeGeneratedImagesToZip(zip);
        } catch (IOException e) {
            throw new BackupException("バックアップアーカイブの作成に失敗しました: " + e.getMessage(), e);
        }

        log.info("バックアップアーカイブを作成しました (mysqlSchemas={}, postgresDatabase={}, size={} bytes)",
                mysqlSchemas, postgresDatabase, zipBytes.size());
        byte[] archive = zipBytes.toByteArray();
        recordBackupDownloadAudit(mysqlSchemas, postgresDatabase, archive.length, metadata.createdAt());
        return archive;
    }

    private void recordBackupDownloadAudit(
            List<String> mysqlSchemas, String postgresDatabase, int sizeBytes, String createdAt) {
        try {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("mysqlSchemas", mysqlSchemas);
            summary.put("postgresDatabase", postgresDatabase);
            summary.put("sizeBytes", sizeBytes);
            summary.put("createdAt", createdAt);
            auditLogService.log(
                    currentActorService.getCurrentActorId(),
                    currentActorService.getCurrentActorKeycloakSub(),
                    AuditLogAction.DB_BACKUP_DOWNLOADED,
                    "DATABASE",
                    null,
                    objectMapper.writeValueAsString(summary),
                    currentActorService.getRemoteIp(),
                    currentActorService.getUserAgent());
        } catch (Exception e) {
            log.warn("バックアップダウンロードの監査ログ記録に失敗しました", e);
        }
    }

    @AuditLog(action = AuditLogAction.DB_RESTORED, resourceType = "DATABASE")
    public void restoreBackup(InputStream archiveStream, boolean confirm, boolean acknowledgeKeyMismatch) {
        adminAuthorizationService.requireAdmin();
        if (!confirm) {
            throw new IllegalArgumentException("リストアは破壊的操作のため、confirm=trueの指定が必要です");
        }

        BackupMetadata metadata = null;
        Map<String, byte[]> mysqlDumps = new LinkedHashMap<>();
        byte[] postgresDump = null;
        Map<String, byte[]> generatedImageFiles = new LinkedHashMap<>();

        try (ZipInputStream zip = new ZipInputStream(archiveStream)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                byte[] content = zip.readAllBytes();
                String name = entry.getName();
                if ("metadata.json".equals(name)) {
                    metadata = objectMapper.readValue(content, BackupMetadata.class);
                } else if (name.startsWith(MYSQL_ENTRY_PREFIX) && name.endsWith(MYSQL_ENTRY_SUFFIX)) {
                    String schema = name.substring(MYSQL_ENTRY_PREFIX.length(),
                            name.length() - MYSQL_ENTRY_SUFFIX.length());
                    mysqlDumps.put(schema, content);
                } else if (name.startsWith(POSTGRES_ENTRY_PREFIX) && name.endsWith(POSTGRES_ENTRY_SUFFIX)) {
                    postgresDump = content;
                } else if (name.startsWith(GENERATED_IMAGES_ENTRY_PREFIX) && !entry.isDirectory()) {
                    String relativePath = name.substring(GENERATED_IMAGES_ENTRY_PREFIX.length());
                    generatedImageFiles.put(relativePath, content);
                }
            }
        } catch (IOException e) {
            throw new BackupException("バックアップアーカイブの読み込みに失敗しました: " + e.getMessage(), e);
        }

        if (mysqlDumps.isEmpty() && postgresDump == null) {
            throw new BackupException("バックアップアーカイブにMySQL/PostgreSQLのダンプが含まれていません");
        }
        if (metadata != null && !metadata.encryptionKeyHash().equals(sha256Hex(encryptionKey)) && !acknowledgeKeyMismatch) {
            throw new BackupException(
                    "このバックアップは異なるAPP_ENCRYPTION_KEYの環境で作成されたものです。"
                            + "現在の環境にリストアすると、サイトの認証情報等の暗号化されたデータが復号できなくなります。"
                            + "これを理解した上で続行する場合は、確認チェックを付けて再実行してください。");
        }

        if (postgresDump != null) {
            verifyPostgresClientCompatibility(metadata);
        }

        // アーカイブ内のエントリ名をそのままスキーマ名としてmysqlコマンドへ渡さず、設定済みの既知スキーマ
        // (backupProperties.mysql.schemas)にのみ制限する(細工されたアーカイブによる想定外の
        // データベースへのアクセスを防ぐ、#570のスキーマ分離の意図を維持するための防御)。
        Set<String> allowedSchemas = new HashSet<>(backupProperties.getMysql().getSchemas());
        int restoredSchemaCount = 0;
        for (Map.Entry<String, byte[]> dump : mysqlDumps.entrySet()) {
            String schema = dump.getKey();
            if (!allowedSchemas.contains(schema)) {
                log.warn("未知のMySQLスキーマ '{}' のダンプはリストア対象から除外しました", schema);
                continue;
            }
            restoreMysqlSchema(schema, dump.getValue());
            restoredSchemaCount++;
        }
        if (postgresDump != null) {
            restorePostgresDatabase(postgresDump);
        }
        restoreGeneratedImages(generatedImageFiles);

        log.info("バックアップからのリストアが完了しました (mysqlSchemas={}, postgresRestored={}, images={})",
                restoredSchemaCount, postgresDump != null, generatedImageFiles.size());
    }

    /**
     * アーカイブを作ったpg_dumpのメジャーバージョンが現在のpg_restoreより新しい場合、復元を始める前に
     * 拒否する(新しいpg_dumpのcustom形式は古いpg_restoreが読めない。issue #1204)。バージョンが
     * 記録されていない(旧アーカイブ)か、解釈できない場合は判別不能として警告のみで続行する
     * (復元可能性を優先し、ブロックしない)。
     */
    private void verifyPostgresClientCompatibility(BackupMetadata metadata) {
        String archiveVersion = metadata == null ? null : metadata.pgDumpVersion();
        if (archiveVersion == null) {
            log.warn("アーカイブにpg_dumpのバージョンが記録されていないため、現在のpg_restoreで復元できるか判別できません"
                    + "(この機能の導入前に作成されたアーカイブ)。復元を続行します");
            return;
        }
        Integer archiveMajor = parsePostgresMajor(archiveVersion);
        if (archiveMajor == null) {
            log.warn("アーカイブのpg_dumpバージョン '{}' を解釈できず、復元できるか判別できません。復元を続行します",
                    archiveVersion);
            return;
        }
        String currentVersion = queryClientVersion("pg_restore");
        Integer currentMajor = parsePostgresMajor(currentVersion);
        if (currentMajor == null) {
            log.warn("現在のpg_restoreバージョン '{}' を解釈できず、復元できるか判別できません。復元を続行します",
                    currentVersion);
            return;
        }
        if (archiveMajor > currentMajor) {
            throw new BackupException("このバックアップは " + archiveVersion + " で作成されたため、"
                    + "現在の " + currentVersion + " では読み込めません(バージョンの不一致)。"
                    + "アーカイブを作成したバージョン以上のpg_restoreが必要です");
        }
    }

    private static Integer parsePostgresMajor(String versionOutput) {
        Matcher matcher = POSTGRES_MAJOR_VERSION.matcher(versionOutput);
        return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
    }

    /**
     * {@code <binary> --version}を実行し、トリムした出力を返す(例: "pg_dump (PostgreSQL) 15.4")。
     * パッケージプライベート(テストで差し替えるため。issue #1204)。
     */
    String queryClientVersion(String binary) {
        byte[] output = runProcess(List.of(binary, "--version"), "PGPASSWORD", "", null, binary + " --version");
        return new String(output, StandardCharsets.UTF_8).strip();
    }

    private byte[] dumpMysqlSchema(String schema) {
        BackupProperties.Mysql mysql = backupProperties.getMysql();
        List<String> command = List.of(
                "mysqldump",
                "--host=" + mysql.getHost(),
                "--port=" + mysql.getPort(),
                "--user=" + mysql.getUser(),
                "--single-transaction",
                "--routines",
                "--triggers",
                // lbs_backupユーザーはスキーマ限定の権限のみを持ち、グローバルなPROCESS権限を持たない
                // (#570のスキーマ分離を維持するため、意図的に付与しない)。--no-tablespacesを付けない場合、
                // mysqldumpはPROCESS権限を要求するテーブルスペース情報の取得を試みて失敗し、標準エラーに
                // 警告を出す(ダンプ自体はexit=0で成功するため実害はないが、ログが紛らわしくなる)。
                "--no-tablespaces",
                schema);
        return runProcess(command, "MYSQL_PWD", mysql.getPassword(), null, "mysqldump(" + schema + ")");
    }

    private void restoreMysqlSchema(String schema, byte[] dump) {
        BackupProperties.Mysql mysql = backupProperties.getMysql();
        List<String> command = List.of(
                "mysql",
                "--host=" + mysql.getHost(),
                "--port=" + mysql.getPort(),
                "--user=" + mysql.getUser(),
                schema);
        runProcess(command, "MYSQL_PWD", mysql.getPassword(), dump, "mysqlによるリストア(" + schema + ")");
    }

    private byte[] dumpPostgresDatabase() {
        BackupProperties.Postgres postgres = backupProperties.getPostgres();
        return runProcess(buildPgDumpCommand(postgres), "PGPASSWORD", postgres.getPassword(), null, "pg_dump");
    }

    /**
     * pg_dumpコマンドを構築する。{@code jgroups_ping}(KeycloakのInfinispan/JGroupsクラスタ
     * 構成員検出用の一時テーブル、issue #1142)を{@code --exclude-table}で丸ごと除外する。
     *
     * <p>Keycloakは稼働中このテーブルへ継続的に書き込む。pg_restore
     * {@code --clean --if-exists}は対象テーブルをDROPしてCREATE TABLE(この時点では
     * PRIMARY KEY制約はまだ無い)し、行データをCOPYした後で初めてPRIMARY KEY制約を追加する
     * 2段階構成のため、CREATE〜制約追加の間の一瞬の間隙でKeycloak自身の書き込みが入ると、
     * ダンプ内の行と{@code address}列が重複し、制約追加時にduplicate keyエラーで
     * リストア全体が失敗する(タイミング依存、約3回に1回の頻度で再現)。
     *
     * <p>検討した他の対策(issue #1142参照):
     * <ul>
     *   <li>リストア前に{@code jgroups_ping}だけをTRUNCATEしてから流し込む — DROP/CREATE/
     *       COPY/制約追加という2段階構成自体は変わらないため、間隙そのものは残り
     *       根本解決にならない。</li>
     *   <li>リストア中はKeycloakコンテナを一時停止する — 根絶できるが、リストアの度に
     *       Keycloakへのログイン・トークン発行が全断する運用上のコストが大きく、
     *       既存の{@link #restoreBackup}の実行モデル(単一トランザクション的に完結させる)
     *       とも整合しない。</li>
     * </ul>
     *
     * <p>{@code jgroups_ping}はクラスタ構成員の一時的な発見状態そのもので、Keycloakの
     * 再起動時に自然に再構築される(ユーザーデータ・認証情報等の永続データではない)ため、
     * バックアップ/リストアの対象から丸ごと除外するのが最も小さく確実な対策である。
     * ダンプに含めなければpg_restoreの{@code --clean --if-exists}もこのテーブルには
     * 何も行わないため、リストア側({@link #restorePostgresDatabase}のコマンド)の変更は
     * 不要になる。
     */
    private static List<String> buildPgDumpCommand(BackupProperties.Postgres postgres) {
        return List.of(
                "pg_dump",
                "--host=" + postgres.getHost(),
                "--port=" + postgres.getPort(),
                "--username=" + postgres.getUser(),
                "--format=custom",
                "--no-password",
                "--exclude-table=public.jgroups_ping",
                postgres.getDatabase());
    }

    private void restorePostgresDatabase(byte[] dump) {
        BackupProperties.Postgres postgres = backupProperties.getPostgres();
        List<String> command = List.of(
                "pg_restore",
                "--host=" + postgres.getHost(),
                "--port=" + postgres.getPort(),
                "--username=" + postgres.getUser(),
                "--dbname=" + postgres.getDatabase(),
                "--clean",
                "--if-exists",
                "--no-password");
        runProcess(command, "PGPASSWORD", postgres.getPassword(), dump, "pg_restore");
    }

    /**
     * mysqldump/mysql/pg_dump/pg_restoreの実行を共通化する。stdinがnullの場合は標準入力を渡さず
     * すぐに閉じる(ダンプ取得時)。stdinが指定されている場合はプロセスへ書き込んでから閉じる
     * (リストア時)。標準出力の内容をバイト列として返す(ダンプ取得時のみ意味を持つ)。
     *
     * <p>パッケージプライベート(テストから直接呼び出すため。issue #1203)。
     */
    byte[] runProcess(List<String> command, String passwordEnvVar, String password, byte[] stdin,
                       String operationLabel) {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().put(passwordEnvVar, password);

        try {
            Process process = builder.start();

            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            Thread stdoutReader = readInBackground(process.getInputStream(), stdout);
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            Thread stderrReader = readInBackground(process.getErrorStream(), stderr);

            // stdinの書き込み中に発生するIOException(典型的には不正なアーカイブを検知した
            // pg_restore等が読み込みを打ち切り、パイプの読み込み側を閉じたことによる
            // "Broken pipe")は、書き込み側から見えた症状であって真の失敗理由ではない。
            // ここでは直ちに例外化せず、プロセスの終了と標準エラー出力の収集を待ってから、
            // 実際に収集できた標準エラーを使って失敗理由を組み立てる(issue #1203)。
            IOException stdinWriteFailure = null;
            if (stdin != null) {
                try (OutputStream out = process.getOutputStream()) {
                    out.write(stdin);
                } catch (IOException e) {
                    stdinWriteFailure = e;
                }
            } else {
                process.getOutputStream().close();
            }

            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            stdoutReader.join();
            stderrReader.join();
            if (!finished) {
                process.destroyForcibly();
                throw new BackupException(operationLabel + "がタイムアウトしました");
            }
            if (process.exitValue() != 0 || stdinWriteFailure != null) {
                throw new BackupException(buildFailureMessage(operationLabel, process.exitValue(),
                        stderr.toString(StandardCharsets.UTF_8), stdinWriteFailure));
            }
            return stdout.toByteArray();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new BackupException(operationLabel + "の実行に失敗しました: " + e.getMessage(), e);
        }
    }

    /**
     * プロセス失敗時のメッセージを組み立てる。標準エラーが取得できていれば、それを失敗理由として
     * 提示する(「Broken pipe」のような書き込み側の症状だけでは根本原因が分からないため。issue
     * #1203)。標準エラーが空の場合に限り、代わりにstdin書き込み時の例外メッセージ(取得できて
     * いれば)を使う。
     */
    private String buildFailureMessage(String operationLabel, int exitValue, String stderrText,
                                        IOException stdinWriteFailure) {
        StringBuilder message = new StringBuilder(operationLabel)
                .append("が失敗しました(exit=").append(exitValue).append(")");
        String trimmedStderr = stderrText.strip();
        if (!trimmedStderr.isEmpty()) {
            message.append(": ").append(trimmedStderr);
        } else if (stdinWriteFailure != null) {
            message.append(": ").append(stdinWriteFailure.getMessage());
        }
        return message.toString();
    }

    /**
     * 生成画像ディレクトリ配下の全ファイルをZIPへ追加する。ディレクトリが存在しない
     * (まだ一度も画像生成していない)場合は何もしない。
     */
    private void writeGeneratedImagesToZip(ZipOutputStream zip) throws IOException {
        if (!Files.exists(generatedImagesDir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(generatedImagesDir)) {
            for (Path path : (Iterable<Path>) paths.filter(Files::isRegularFile)::iterator) {
                String relativePath = generatedImagesDir.relativize(path).toString().replace('\\', '/');
                writeZipEntry(zip, GENERATED_IMAGES_ENTRY_PREFIX + relativePath, Files.readAllBytes(path));
            }
        }
    }

    /**
     * バックアップに含まれる生成画像ファイルを保存先ディレクトリへ書き戻す(同名ファイルは上書き)。
     * 既存ファイルでバックアップに含まれないものは削除しない(意図しないデータ消失を避けるため)。
     *
     * <p>アーカイブ内のエントリ名(zipのファイル名部分)はそのままファイルシステムパスの解決に使わず、
     * generatedImagesDir配下に正規化後も収まることを検証する(Zip Slip対策)。MySQLスキーマ名の
     * 許可リスト検証と同様、検証に失敗したエントリはリストア対象から除外し警告ログを出す。
     */
    private void restoreGeneratedImages(Map<String, byte[]> files) {
        Path normalizedBaseDir = generatedImagesDir.toAbsolutePath().normalize();
        for (var entry : files.entrySet()) {
            String relativePath = entry.getKey();
            Path target = normalizedBaseDir.resolve(relativePath).normalize();
            if (!target.startsWith(normalizedBaseDir)) {
                log.warn("生成画像ファイル '{}' はgeneratedImagesDirの外を指すパスに正規化されるため、"
                        + "リストア対象から除外しました", relativePath);
                continue;
            }
            try {
                Files.createDirectories(target.getParent());
                Files.write(target, entry.getValue());
            } catch (IOException e) {
                throw new BackupException("生成画像ファイル '" + relativePath + "' の書き込みに失敗しました: " + e.getMessage(), e);
            }
        }
    }

    private void writeZipEntry(ZipOutputStream zip, String name, byte[] content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new BackupException("SHA-256アルゴリズムが利用できません", e);
        }
    }

    private Thread readInBackground(InputStream source, ByteArrayOutputStream sink) {
        Thread thread = new Thread(() -> {
            try {
                source.transferTo(sink);
            } catch (IOException ignored) {
                // プロセス終了に伴うストリームクローズは無視する
            }
        });
        thread.start();
        return thread;
    }
}
