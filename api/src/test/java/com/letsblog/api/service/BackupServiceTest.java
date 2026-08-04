package com.letsblog.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * BackupServiceの回帰テスト。mysqldump/mysqlの実プロセス実行はdocker-compose環境での
 * 統合テストに委ね、ここではadmin権限ゲート・confirmフラグ検証・暗号化キー不一致検出
 * (いずれも実プロセスを起動する前に評価される)を中心に検証する。
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

    private String sha256Hex(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(value.getBytes()));
    }

    @Test
    void createBackup_admin権限がなければForbidden() {
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です"))
                .when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> service.createBackup());
    }

    @Test
    void restoreBackup_admin権限がなければForbidden() {
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です"))
                .when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class,
                () -> service.restoreBackup(new ByteArrayInputStream(new byte[0]), true, false));
    }

    @Test
    void restoreBackup_confirmがfalseなら例外() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.restoreBackup(new ByteArrayInputStream(new byte[0]), false, false));

        org.hamcrest.MatcherAssert.assertThat(exception.getMessage(),
                org.hamcrest.Matchers.containsString("confirm=true"));
        verify(adminAuthorizationService).requireAdmin();
    }

    @Test
    void restoreBackup_暗号化キーが不一致でacknowledgeなしなら例外() throws Exception {
        byte[] archive = buildArchive(sha256Hex("different-key"));

        BackupException exception = assertThrows(BackupException.class,
                () -> service.restoreBackup(new ByteArrayInputStream(archive), true, false));

        org.hamcrest.MatcherAssert.assertThat(exception.getMessage(),
                org.hamcrest.Matchers.containsString("APP_ENCRYPTION_KEY"));
    }
}
