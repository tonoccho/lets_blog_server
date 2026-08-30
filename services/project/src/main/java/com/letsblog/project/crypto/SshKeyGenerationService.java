package com.letsblog.project.crypto;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * サーバー側でSSH鍵ペア(Ed25519)を生成する。OpenSSH形式の秘密鍵をJavaだけで安全に
 * シリアライズする実用的な手段がないため、ホストの`ssh-keygen`コマンドに委譲する
 * (project-service/Dockerfileでopenssh-clientパッケージを導入済み)。
 */
@Component
@Slf4j
public class SshKeyGenerationService {

    private static final int TIMEOUT_SECONDS = 10;

    public record SshKeyPair(String privateKeyPem, String publicKeyLine) {
    }

    public SshKeyPair generateEd25519(String comment) {
        Path dir = null;
        try {
            dir = Files.createTempDirectory("letsblog-sshkeygen",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            Path keyFile = dir.resolve("id_ed25519");
            String safeComment = comment == null ? "" : comment;

            Process process = new ProcessBuilder(
                    "ssh-keygen", "-t", "ed25519", "-N", "", "-C", safeComment, "-f", keyFile.toString())
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new SshKeyGenerationException("ssh-keygenがタイムアウトしました");
            }
            if (process.exitValue() != 0) {
                String output = new String(process.getInputStream().readAllBytes());
                throw new SshKeyGenerationException("ssh-keygenが失敗しました: " + output);
            }

            String privateKeyPem = Files.readString(keyFile);
            String publicKeyLine = Files.readString(dir.resolve("id_ed25519.pub")).strip();
            return new SshKeyPair(privateKeyPem, publicKeyLine);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new SshKeyGenerationException("SSH鍵ペアの生成に失敗しました: " + e.getMessage(), e);
        } finally {
            if (dir != null) {
                deleteRecursively(dir);
            }
        }
    }

    private void deleteRecursively(Path dir) {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    log.warn("SSH鍵生成の一時ファイル削除に失敗しました: {}", path);
                }
            });
        } catch (IOException e) {
            log.warn("SSH鍵生成の一時ディレクトリ削除に失敗しました: {}", dir);
        }
    }

    public static class SshKeyGenerationException extends RuntimeException {
        public SshKeyGenerationException(String message) {
            super(message);
        }

        public SshKeyGenerationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
