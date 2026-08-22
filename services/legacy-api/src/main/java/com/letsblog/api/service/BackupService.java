package com.letsblog.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.api.aop.AuditLog;
import com.letsblog.api.domain.AuditLogAction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
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
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Let's Blogアプリ自身のデータ(MySQLデータベース + 生成画像ファイル)をバックアップ/リストアする。
 * managed WordPressサイト個別のDB/ファイルは対象外(各サイト自体のバックアップ手段に委ねる)。
 * バックアップはZIPアーカイブとして db.sql・metadata.json・generated-images/ を1つにまとめる。
 * metadata.jsonにはバックアップ作成時点のAPP_ENCRYPTION_KEYのハッシュ値を記録し、リストア時に
 * 現在の環境のキーと一致するか検証する(サイト認証情報等はこのキーで暗号化されているため、
 * 異なるキーの環境へリストアすると復号できなくなり実質的にデータが壊れるのを防ぐため)。
 * VscodeExtensionBuildServiceと同様、ProcessBuilderでホストコマンドに委譲するパターンを踏襲する。
 * バックアップ/リストアはいずれもadmin限定の破壊的操作になり得るため、AdminAuthorizationServiceで
 * 権限を確認した上で実行し、監査ログに記録する。
 */
@Service
@Slf4j
public class BackupService {

    private static final int TIMEOUT_SECONDS = 300;
    private static final String GENERATED_IMAGES_ENTRY_PREFIX = "generated-images/";

    private final String host;
    private final String port;
    private final String database;
    private final String user;
    private final String password;
    private final Path generatedImagesDir;
    private final String encryptionKey;
    private final AdminAuthorizationService adminAuthorizationService;
    private final ObjectMapper objectMapper;

    public BackupService(
            @Value("${app.backup-mysql-host}") String host,
            @Value("${app.backup-mysql-port}") String port,
            @Value("${app.backup-mysql-database}") String database,
            @Value("${app.backup-mysql-user}") String user,
            @Value("${app.backup-mysql-password}") String password,
            @Value("${app.generated-images-storage-path}") String generatedImagesStoragePath,
            @Value("${app.encryption-key}") String encryptionKey,
            AdminAuthorizationService adminAuthorizationService,
            ObjectMapper objectMapper) {
        this.host = host;
        this.port = port;
        this.database = database;
        this.user = user;
        this.password = password;
        this.generatedImagesDir = Path.of(generatedImagesStoragePath);
        this.encryptionKey = encryptionKey;
        this.adminAuthorizationService = adminAuthorizationService;
        this.objectMapper = objectMapper;
    }

    public record BackupMetadata(String encryptionKeyHash, String createdAt) {
    }

    @AuditLog(action = AuditLogAction.DB_BACKUP_DOWNLOADED, resourceType = "DATABASE")
    public byte[] createBackup() {
        adminAuthorizationService.requireAdmin();

        byte[] dbDump = dumpDatabase();
        BackupMetadata metadata = new BackupMetadata(sha256Hex(encryptionKey), Instant.now().toString());

        ByteArrayOutputStream zipBytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(zipBytes)) {
            writeZipEntry(zip, "db.sql", dbDump);
            writeZipEntry(zip, "metadata.json", objectMapper.writeValueAsBytes(metadata));
            writeGeneratedImagesToZip(zip);
        } catch (IOException e) {
            throw new BackupException("バックアップアーカイブの作成に失敗しました: " + e.getMessage(), e);
        }

        log.info("バックアップアーカイブを作成しました (database={}, size={} bytes)", database, zipBytes.size());
        return zipBytes.toByteArray();
    }

    @AuditLog(action = AuditLogAction.DB_RESTORED, resourceType = "DATABASE")
    public void restoreBackup(InputStream archiveStream, boolean confirm, boolean acknowledgeKeyMismatch) {
        adminAuthorizationService.requireAdmin();
        if (!confirm) {
            throw new IllegalArgumentException("リストアは破壊的操作のため、confirm=trueの指定が必要です");
        }

        byte[] dbDump = null;
        BackupMetadata metadata = null;
        java.util.Map<String, byte[]> generatedImageFiles = new java.util.LinkedHashMap<>();

        try (ZipInputStream zip = new ZipInputStream(archiveStream)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                byte[] content = zip.readAllBytes();
                if ("db.sql".equals(entry.getName())) {
                    dbDump = content;
                } else if ("metadata.json".equals(entry.getName())) {
                    metadata = objectMapper.readValue(content, BackupMetadata.class);
                } else if (entry.getName().startsWith(GENERATED_IMAGES_ENTRY_PREFIX) && !entry.isDirectory()) {
                    String relativePath = entry.getName().substring(GENERATED_IMAGES_ENTRY_PREFIX.length());
                    generatedImageFiles.put(relativePath, content);
                }
            }
        } catch (IOException e) {
            throw new BackupException("バックアップアーカイブの読み込みに失敗しました: " + e.getMessage(), e);
        }

        if (dbDump == null) {
            throw new BackupException("バックアップアーカイブにdb.sqlが含まれていません");
        }
        if (metadata != null && !metadata.encryptionKeyHash().equals(sha256Hex(encryptionKey)) && !acknowledgeKeyMismatch) {
            throw new BackupException(
                    "このバックアップは異なるAPP_ENCRYPTION_KEYの環境で作成されたものです。"
                            + "現在の環境にリストアすると、サイトの認証情報等の暗号化されたデータが復号できなくなります。"
                            + "これを理解した上で続行する場合は、確認チェックを付けて再実行してください。");
        }

        restoreDatabase(new ByteArrayInputStream(dbDump));
        restoreGeneratedImages(generatedImageFiles);

        log.info("バックアップからのリストアが完了しました (database={}, images={})",
                database, generatedImageFiles.size());
    }

    private byte[] dumpDatabase() {
        ProcessBuilder builder = new ProcessBuilder(
                "mysqldump",
                "--host=" + host,
                "--port=" + port,
                "--user=" + user,
                "--single-transaction",
                "--routines",
                "--triggers",
                database);
        builder.environment().put("MYSQL_PWD", password);

        try {
            Process process = builder.start();
            process.getOutputStream().close();

            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            Thread stdoutReader = readInBackground(process.getInputStream(), stdout);
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            Thread stderrReader = readInBackground(process.getErrorStream(), stderr);

            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            stdoutReader.join();
            stderrReader.join();
            if (!finished) {
                process.destroyForcibly();
                throw new BackupException("mysqldumpがタイムアウトしました");
            }
            if (process.exitValue() != 0) {
                throw new BackupException("mysqldumpが失敗しました(exit=" + process.exitValue() + "): "
                        + stderr.toString(StandardCharsets.UTF_8));
            }
            return stdout.toByteArray();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new BackupException("mysqldumpの実行に失敗しました: " + e.getMessage(), e);
        }
    }

    private void restoreDatabase(InputStream dumpStream) {
        ProcessBuilder builder = new ProcessBuilder(
                "mysql",
                "--host=" + host,
                "--port=" + port,
                "--user=" + user,
                database);
        builder.environment().put("MYSQL_PWD", password);

        try {
            Process process = builder.start();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            Thread stderrReader = readInBackground(process.getErrorStream(), stderr);
            Thread stdoutDrainer = readInBackground(process.getInputStream(), new ByteArrayOutputStream());

            try (OutputStream stdin = process.getOutputStream()) {
                dumpStream.transferTo(stdin);
            }

            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            stderrReader.join();
            stdoutDrainer.join();
            if (!finished) {
                process.destroyForcibly();
                throw new BackupException("mysqlによるリストアがタイムアウトしました");
            }
            if (process.exitValue() != 0) {
                throw new BackupException("リストアが失敗しました(exit=" + process.exitValue() + "): "
                        + stderr.toString(StandardCharsets.UTF_8));
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new BackupException("mysqlの実行に失敗しました: " + e.getMessage(), e);
        }
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
     */
    private void restoreGeneratedImages(java.util.Map<String, byte[]> files) {
        for (var entry : files.entrySet()) {
            try {
                Path target = generatedImagesDir.resolve(entry.getKey());
                Files.createDirectories(target.getParent());
                Files.write(target, entry.getValue());
            } catch (IOException e) {
                throw new BackupException("生成画像ファイル '" + entry.getKey() + "' の書き込みに失敗しました: " + e.getMessage(), e);
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
