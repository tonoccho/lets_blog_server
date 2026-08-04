package com.letsblog.api.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * ComfyUIで生成した画像を app.generated-images-storage-path 配下へ永続化するサービス。
 * 保存パスは {projectIdOrGlobal}/{sha256}.png 形式(projectId未指定時は"global")とし、
 * DBのgenerated_images.file_pathにはこの相対パスを記録する。
 */
@Service
public class GeneratedImageStorageService {

    private final Path storageDir;

    public GeneratedImageStorageService(@Value("${app.generated-images-storage-path}") String storagePath) {
        this.storageDir = Path.of(storagePath);
    }

    public String store(Long projectId, byte[] data) {
        String projectIdOrGlobal = projectId != null ? String.valueOf(projectId) : "global";
        String sha256 = sha256Hex(data);
        String relativePath = projectIdOrGlobal + "/" + sha256 + ".png";
        try {
            Path targetFile = storageDir.resolve(relativePath);
            Files.createDirectories(targetFile.getParent());
            Files.write(targetFile, data);
        } catch (IOException e) {
            throw new AiServiceException("生成画像の保存に失敗しました: " + e.getMessage(), e);
        }
        return relativePath;
    }

    public byte[] load(String relativeFilePath) {
        try {
            return Files.readAllBytes(storageDir.resolve(relativeFilePath));
        } catch (IOException e) {
            throw new AiServiceException("生成画像の読み込みに失敗しました: " + e.getMessage(), e);
        }
    }

    private String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new AiServiceException("SHA-256アルゴリズムが利用できません", e);
        }
    }
}
