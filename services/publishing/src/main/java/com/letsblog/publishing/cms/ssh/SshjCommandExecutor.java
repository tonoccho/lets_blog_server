package com.letsblog.publishing.cms.ssh;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.common.IOUtils;
import net.schmizz.sshj.common.SecurityUtils;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.transport.verification.HostKeyVerifier;
import net.schmizz.sshj.xfer.InMemoryDestFile;
import net.schmizz.sshj.xfer.InMemorySourceFile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * sshjを使ったSshCommandExecutorの実装。ホスト単位でSSHClientをプーリングし使い回す
 * (フィードバック対応前は呼び出しごとに接続・切断していたが、ハンドシェイクの
 * オーバーヘッドと同時接続数を減らすため、接続を保持して再利用する方式に変更した)。
 * 同一ホストへの操作は{@link PooledConnection#lock}で直列化する(1接続を複数スレッドから
 * 同時に使わない。execAll()が元々1接続にまとめて複数コマンドを順番に実行していたのと同じ考え方)。
 * 接続が切れていた場合は自動的に再接続し、実行中にIOExceptionが起きた場合も1回だけ再接続して
 * リトライする。アイドル状態が続いた接続は{@link #evictIdleConnections()}で定期的に閉じる。
 *
 * ホスト鍵のfingerprintは接続確立のたびにローカル変数で捕捉しSshCommandResultに載せて返す
 * (シングルトンbeanのインスタンスフィールドに保持すると、並行接続時にfingerprintを
 * 取り違える競合状態になるため避けている)。
 */
@Component
@Slf4j
public class SshjCommandExecutor implements SshCommandExecutor {

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int COMMAND_TIMEOUT_SECONDS = 30;
    private static final long IDLE_TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(5);

    private final Map<String, PooledConnection> pool = new ConcurrentHashMap<>();

    @Override
    public SshCommandResult exec(SshConnectionParams params, String command, byte[] stdin) {
        return withConnection(params, client -> execInSession(client, command, stdin, currentFingerprint(params)));
    }

    @Override
    public List<SshCommandResult> execAll(SshConnectionParams params, List<String> commands) {
        return withConnection(params, client -> {
            List<SshCommandResult> results = new ArrayList<>();
            for (String command : commands) {
                results.add(execInSession(client, command, null, currentFingerprint(params)));
            }
            return results;
        });
    }

    @Override
    public void putFile(SshConnectionParams params, byte[] data, String remotePath) {
        withConnection(params, client -> {
            try (SFTPClient sftp = client.newSFTPClient()) {
                sftp.put(new ByteArraySourceFile(remotePath, data), remotePath);
                return null;
            } catch (IOException e) {
                throw new SshOperationException("SFTP転送に失敗しました (host=" + params.host() + ", path=" + remotePath
                        + "): " + e.getMessage(), e);
            }
        });
    }

    @Override
    public byte[] getFile(SshConnectionParams params, String remotePath) {
        return withConnection(params, client -> {
            try (SFTPClient sftp = client.newSFTPClient()) {
                ByteArrayDestFile dest = new ByteArrayDestFile();
                sftp.get(remotePath, dest);
                return dest.toByteArray();
            } catch (IOException e) {
                throw new SshOperationException("SFTPダウンロードに失敗しました (host=" + params.host() + ", path=" + remotePath
                        + "): " + e.getMessage(), e);
            }
        });
    }

    @Override
    public void removeFile(SshConnectionParams params, String remotePath) {
        try {
            withConnection(params, client -> {
                try (SFTPClient sftp = client.newSFTPClient()) {
                    sftp.rm(remotePath);
                    return null;
                } catch (IOException e) {
                    throw new SshOperationException("リモート一時ファイルの削除に失敗しました (host=" + params.host()
                            + ", path=" + remotePath + "): " + e.getMessage(), e);
                }
            });
        } catch (SshOperationException e) {
            log.warn(e.getMessage());
        }
    }

    /**
     * ホスト単位のプールから接続を取得(無ければ確立)し、actionを実行する。実行中に接続断が
     * 疑われるIOExceptionが起きた場合は、その接続を破棄して1回だけ再接続・再実行する。
     */
    private <T> T withConnection(SshConnectionParams params, ConnectionAction<T> action) {
        PooledConnection connection = pool.computeIfAbsent(poolKeyOf(params), k -> new PooledConnection());
        connection.lock.lock();
        try {
            ensureConnected(connection, params);
            try {
                T result = action.run(connection.client);
                connection.lastUsedAtMillis = System.currentTimeMillis();
                return result;
            } catch (IOException e) {
                log.warn("SSHコマンド実行中にエラーが発生したため再接続します(host={}): {}", params.host(), e.getMessage());
                closeQuietly(connection);
                ensureConnected(connection, params);
                try {
                    T result = action.run(connection.client);
                    connection.lastUsedAtMillis = System.currentTimeMillis();
                    return result;
                } catch (IOException retryFailure) {
                    closeQuietly(connection);
                    throw new SshOperationException(
                            "SSHコマンド実行に失敗しました (host=" + params.host() + "): " + retryFailure.getMessage(),
                            retryFailure);
                }
            }
        } finally {
            connection.lock.unlock();
        }
    }

    private void ensureConnected(PooledConnection connection, SshConnectionParams params) {
        if (connection.client != null && connection.client.isConnected()) {
            return;
        }
        try {
            connection.client = connect(params, connection);
        } catch (IOException e) {
            connection.client = null;
            throw new SshOperationException("SSH接続に失敗しました (host=" + params.host() + "): " + e.getMessage(), e);
        }
    }

    private String currentFingerprint(SshConnectionParams params) {
        PooledConnection connection = pool.get(poolKeyOf(params));
        return connection != null ? connection.fingerprint : null;
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

    private SSHClient connect(SshConnectionParams params, PooledConnection connection) throws IOException {
        SSHClient client = new SSHClient();
        client.addHostKeyVerifier(tofuVerifier(params, connection));
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
     * 実際に提示されたホスト鍵のfingerprintを受理してconnectionに記録する(TOFU)。
     * fingerprint設定済みの場合は完全一致のみ許可する。
     */
    private HostKeyVerifier tofuVerifier(SshConnectionParams params, PooledConnection connection) {
        return new HostKeyVerifier() {
            @Override
            public boolean verify(String hostname, int port, PublicKey key) {
                String fingerprint = SecurityUtils.getFingerprint(key);
                connection.fingerprint = fingerprint;
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

    /**
     * プール上限の目安として、一定時間使われていない接続を定期的に閉じる。
     * ロックを保持中(実行中)の接続はtryLockが失敗するのでスキップし、次回に回す。
     */
    @Scheduled(fixedDelay = 60_000)
    void evictIdleConnections() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, PooledConnection> entry : pool.entrySet()) {
            PooledConnection connection = entry.getValue();
            if (connection.client == null || now - connection.lastUsedAtMillis < IDLE_TIMEOUT_MILLIS) {
                continue;
            }
            if (!connection.lock.tryLock()) {
                continue;
            }
            try {
                if (connection.client != null && now - connection.lastUsedAtMillis >= IDLE_TIMEOUT_MILLIS) {
                    closeQuietly(connection);
                }
            } finally {
                connection.lock.unlock();
            }
        }
    }

    @PreDestroy
    void closeAllConnections() {
        for (PooledConnection connection : pool.values()) {
            connection.lock.lock();
            try {
                closeQuietly(connection);
            } finally {
                connection.lock.unlock();
            }
        }
    }

    private void closeQuietly(PooledConnection connection) {
        if (connection.client == null) {
            return;
        }
        try {
            connection.client.close();
        } catch (IOException ignored) {
            // 切断失敗は無視する(どうせ破棄するため)
        } finally {
            connection.client = null;
        }
    }

    private String poolKeyOf(SshConnectionParams params) {
        return params.host() + ":" + params.port() + ":" + params.user() + ":" + sha256(params.privateKeyPem());
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Base64.getEncoder().encodeToString(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256が利用できません", e);
        }
    }

    @FunctionalInterface
    private interface ConnectionAction<T> {
        T run(SSHClient client) throws IOException;
    }

    /**
     * ホスト単位でプールする1件の接続。clientはnull(未接続)になりうる。lockでこの接続への
     * アクセスを直列化する(接続の確立・利用・破棄はすべてlock保持中にのみ行う)。
     */
    private static final class PooledConnection {
        private final ReentrantLock lock = new ReentrantLock();
        private volatile SSHClient client;
        private volatile String fingerprint;
        private volatile long lastUsedAtMillis = System.currentTimeMillis();
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

    private static final class ByteArrayDestFile extends InMemoryDestFile {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        @Override
        public long getLength() {
            return buffer.size();
        }

        @Override
        public OutputStream getOutputStream() {
            return buffer;
        }

        @Override
        public OutputStream getOutputStream(boolean append) {
            return buffer;
        }

        byte[] toByteArray() {
            return buffer.toByteArray();
        }
    }
}
