package com.letsblog.media.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * ComfyUIで生成した画像を app.generated-images-storage-path 配下へ永続化するサービス。
 * 保存パスは {projectIdOrGlobal}/{4桁連番}.{拡張子(既定png)} 形式(projectId未指定時は"global")とし、
 * DBのgenerated_images.file_pathにはこの相対パスを記録する。
 * 連番はGeneratedImageSequenceServiceがプロジェクト単位で払い出す。
 */
@Service
public class GeneratedImageStorageService {

    private final Path storageDir;
    private final GeneratedImageSequenceService sequenceService;

    public GeneratedImageStorageService(
            @Value("${app.generated-images-storage-path}") String storagePath,
            GeneratedImageSequenceService sequenceService) {
        this.storageDir = Path.of(storagePath);
        this.sequenceService = sequenceService;
    }

    public String store(Long projectId, byte[] data) {
        return store(projectId, data, "png");
    }

    /** 拡張子を指定して保存する(アップロードされたJPEGを.jpgで保存するため、issue #1599)。 */
    public String store(Long projectId, byte[] data, String extension) {
        String projectIdOrGlobal = projectId != null ? String.valueOf(projectId) : "global";
        int sequence = nextSequenceWithRetry(projectIdOrGlobal);
        String relativePath = projectIdOrGlobal + "/" + String.format("%04d", sequence) + "." + extension;
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

    public void delete(String relativeFilePath) {
        try {
            Files.deleteIfExists(storageDir.resolve(relativeFilePath));
        } catch (IOException e) {
            throw new AiServiceException("生成画像の削除に失敗しました: " + e.getMessage(), e);
        }
    }

    private int nextSequenceWithRetry(String projectKey) {
        try {
            return sequenceService.nextSequence(projectKey);
        } catch (DataIntegrityViolationException e) {
            // 初回の連番行作成が同時に競合した場合のみ発生する。作成済みの行をロックして採番し直す。
            return sequenceService.nextSequence(projectKey);
        }
    }
}
