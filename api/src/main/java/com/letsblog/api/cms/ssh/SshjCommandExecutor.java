package com.letsblog.api.cms.ssh;

import lombok.extern.slf4j.Slf4j;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.common.IOUtils;
import net.schmizz.sshj.common.SecurityUtils;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.transport.verification.HostKeyVerifier;
import net.schmizz.sshj.xfer.InMemorySourceFile;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * sshjを使ったSshCommandExecutorの実装。exec()の呼び出しごとに新規接続・認証・切断する
 * (コネクションプーリングはしない。疎通確認・プロビジョニングは頻度が低いため、
 * ハンドシェイクのオーバーヘッドよりも実装のシンプルさを優先する)。execAll()は例外的に、
 * 複数コマンドの実行に対して接続確立を1回だけに抑える(比較テーブルの初期表示のように
 * 同じホストへ複数種類の一覧取得をまとめて行う場合、種類ごとに新規接続すると同時接続数を
 * 制限する共有ホスティングでConnection refusedになりやすいため)。
 *
 * ホスト鍵のfingerprintは接続のたびにローカル変数で捕捉しSshCommandResultに載せて返す
 * (シングルトンbeanのインスタンスフィールドに保持すると、並行接続時にfingerprintを
 * 取り違える競合状態になるため避けている)。
 */
@Component
@Slf4j
public class SshjCommandExecutor implements SshCommandExecutor {

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int COMMAND_TIMEOUT_SECONDS = 30;

    @Override
    public SshCommandResult exec(SshConnectionParams params, String command, byte[] stdin) {
        AtomicReference<String> observedFingerprint = new AtomicReference<>();
        try (SSHClient client = connect(params, observedFingerprint)) {
            return execInSession(client, command, stdin, observedFingerprint.get());
        } catch (IOException e) {
            throw new SshOperationException("SSHコマンド実行に失敗しました (host=" + params.host() + "): " + e.getMessage(), e);
        }
    }

    @Override
    public List<SshCommandResult> execAll(SshConnectionParams params, List<String> commands) {
        AtomicReference<String> observedFingerprint = new AtomicReference<>();
        try (SSHClient client = connect(params, observedFingerprint)) {
            List<SshCommandResult> results = new ArrayList<>();
            for (String command : commands) {
                results.add(execInSession(client, command, null, observedFingerprint.get()));
            }
            return results;
        } catch (IOException e) {
            throw new SshOperationException("SSHコマンド実行に失敗しました (host=" + params.host() + "): " + e.getMessage(), e);
        }
    }

    private SshCommandResult execInSession(SSHClient client, String command, byte[] stdin, String fingerprint)
            throws IOException {
        try (Session session = client.startSession()) {
            Session.Command cmd = session.exec(command);
            if (stdin != null) {
                cmd.getOutputStream().write(stdin);
                cmd.getOutputStream().close();
            }
            String stdout = IOUtils.readFully(cmd.getInputStream()).toString(StandardCharsets.UTF_8);
            String stderr = IOUtils.readFully(cmd.getErrorStream()).toString(StandardCharsets.UTF_8);
            cmd.join(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            Integer exitStatus = cmd.getExitStatus();
            return new SshCommandResult(exitStatus != null ? exitStatus : -1, stdout, stderr, fingerprint);
        }
    }

    @Override
    public void putFile(SshConnectionParams params, byte[] data, String remotePath) {
        AtomicReference<String> observedFingerprint = new AtomicReference<>();
        try (SSHClient client = connect(params, observedFingerprint); SFTPClient sftp = client.newSFTPClient()) {
            sftp.put(new ByteArraySourceFile(remotePath, data), remotePath);
        } catch (IOException e) {
            throw new SshOperationException("SFTP転送に失敗しました (host=" + params.host() + ", path=" + remotePath + "): "
                    + e.getMessage(), e);
        }
    }

    @Override
    public void removeFile(SshConnectionParams params, String remotePath) {
        AtomicReference<String> observedFingerprint = new AtomicReference<>();
        try (SSHClient client = connect(params, observedFingerprint); SFTPClient sftp = client.newSFTPClient()) {
            sftp.rm(remotePath);
        } catch (IOException e) {
            log.warn("リモート一時ファイルの削除に失敗しました (host={}, path={}): {}", params.host(), remotePath, e.getMessage());
        }
    }

    private SSHClient connect(SshConnectionParams params, AtomicReference<String> observedFingerprint)
            throws IOException {
        SSHClient client = new SSHClient();
        client.addHostKeyVerifier(tofuVerifier(params, observedFingerprint));
        client.setConnectTimeout(CONNECT_TIMEOUT_MS);
        client.connect(params.host(), params.port());

        Path keyFile = null;
        try {
            keyFile = writePrivateKeyToTempFile(params.privateKeyPem());
            client.authPublickey(params.user(), keyFile.toString());
            return client;
        } catch (IOException | RuntimeException e) {
            client.close();
            throw e;
        } finally {
            if (keyFile != null) {
                try {
                    Files.deleteIfExists(keyFile);
                } catch (IOException ignored) {
                    // 一時ファイル削除失敗は握りつぶす(内容は秘密鍵だが、プロセス終了で/tmpは掃除される前提)
                }
            }
        }
    }

    /**
     * ホスト鍵検証。paramsにfingerprintが未設定(初回接続=鍵生成直後)の場合は、
     * 実際に提示されたホスト鍵のfingerprintを受理してobservedFingerprintに記録する(TOFU)。
     * fingerprint設定済みの場合は完全一致のみ許可する。
     */
    private HostKeyVerifier tofuVerifier(SshConnectionParams params, AtomicReference<String> observedFingerprint) {
        return new HostKeyVerifier() {
            @Override
            public boolean verify(String hostname, int port, PublicKey key) {
                String fingerprint = SecurityUtils.getFingerprint(key);
                observedFingerprint.set(fingerprint);
                if (params.hostKeyFingerprint() == null || params.hostKeyFingerprint().isBlank()) {
                    return true;
                }
                return params.hostKeyFingerprint().equals(fingerprint);
            }

            @Override
            public List<String> findExistingAlgorithms(String hostname, int port) {
                return List.of();
            }
        };
    }

    private Path writePrivateKeyToTempFile(String privateKeyPem) throws IOException {
        Path dir = Files.createTempDirectory("letsblog-sshkey",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Path file = dir.resolve("id_ed25519");
        Files.writeString(file, privateKeyPem);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        return file;
    }

    private static final class ByteArraySourceFile extends InMemorySourceFile {
        private final String name;
        private final byte[] data;

        private ByteArraySourceFile(String remotePath, byte[] data) {
            int slash = remotePath.lastIndexOf('/');
            this.name = slash >= 0 ? remotePath.substring(slash + 1) : remotePath;
            this.data = data;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public long getLength() {
            return data.length;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(data);
        }
    }
}
