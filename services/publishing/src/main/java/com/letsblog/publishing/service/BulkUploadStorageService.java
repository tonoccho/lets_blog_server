package com.letsblog.publishing.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 一括管理(プラグイン/テーマのzipアップロード)で受け取ったファイルを、
 * ロールフォワードで後から再利用できるようプロジェクト単位で永続化する。
 * APIコンテナは元々ステートレスだが、ロールフォワードにはアップロード内容の再現が
 * 必須なため、本サービス専用の永続ボリューム(app.bulk-upload-storage-path)を利用する。
 *
 * <p>legacy-apiの{@code com.letsblog.api.service.BulkUploadStorageService}をpublishing-serviceへ
 * 移設したもの(issue #708、Epic #551 C6-2)。
 */
@Service
public class BulkUploadStorageService {

    private final Path rootDir;

    public BulkUploadStorageService(@Value("${app.bulk-upload-storage-path}") String rootDir) {
        this.rootDir = Path.of(rootDir);
    }

    public StoredZip store(Long projectId, byte[] bytes, String originalFilename) throws IOException {
        String sha256 = sha256Hex(bytes);
        Path dir = rootDir.resolve(String.valueOf(projectId));
        Files.createDirectories(dir);
        Path target = dir.resolve(sha256 + ".zip");
        if (!Files.exists(target)) {
            Files.write(target, bytes);
        }
        return new StoredZip(projectId + "/" + sha256 + ".zip", sha256, originalFilename);
    }

    public byte[] load(String storagePath) throws IOException {
        Path target = rootDir.resolve(storagePath);
        if (!Files.exists(target)) {
            throw new IOException("保存済みファイルが見つかりません: " + storagePath);
        }
        return Files.readAllBytes(target);
    }

    /**
     * プロジェクト削除時に呼び出す。ファイルシステム上の保存済みzipは明示的に削除する。
     */
    public void deleteAll(Long projectId) {
        FileSystemUtils.deleteRecursively(rootDir.resolve(String.valueOf(projectId)).toFile());
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256アルゴリズムが利用できません", e);
        }
    }

    public record StoredZip(String storagePath, String sha256, String originalFilename) {}
}
