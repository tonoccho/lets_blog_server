package com.letsblog.platform.controller;

import com.letsblog.platform.service.BackupException;
import com.letsblog.platform.service.BackupService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Let's Blog全体(全サービスのMySQLスキーマ + Keycloak PostgreSQL + 生成画像ファイル)のバックアップ
 * (ダウンロード)/リストアを提供する。legacy-apiのBackupControllerと同じAPI形状のまま
 * platform-serviceへ移設・対象拡張したもの(issue #694、C10-2)。権限確認・確認フラグの検証・
 * 監査ログ記録はBackupService側で行う(いずれもadmin限定の破壊的操作)。gatewayの
 * {@code /api/backup/**}ルートを経由する。
 */
@RestController
@RequestMapping("/api/backup")
public class BackupController {

    private static final DateTimeFormatter FILENAME_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final BackupService backupService;

    public BackupController(BackupService backupService) {
        this.backupService = backupService;
    }

    @GetMapping("/download")
    public ResponseEntity<byte[]> download() {
        byte[] archive = backupService.createBackup();
        String filename = "lets-blog-backup-" + LocalDateTime.now().format(FILENAME_TIMESTAMP) + ".zip";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(archive);
    }

    @PostMapping(value = "/restore", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Void> restore(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "confirm", defaultValue = "false") boolean confirm,
            @RequestParam(value = "acknowledgeKeyMismatch", defaultValue = "false") boolean acknowledgeKeyMismatch) {
        try {
            backupService.restoreBackup(file.getInputStream(), confirm, acknowledgeKeyMismatch);
        } catch (IOException e) {
            throw new BackupException("アップロードされたバックアップアーカイブの読み込みに失敗しました: " + e.getMessage(), e);
        }
        return ResponseEntity.noContent().build();
    }
}
