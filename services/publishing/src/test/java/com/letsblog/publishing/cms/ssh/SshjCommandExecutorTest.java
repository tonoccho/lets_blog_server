package com.letsblog.publishing.cms.ssh;

import com.letsblog.publishing.cms.ssh.SshCommandExecutor.SshCommandResult;
import com.letsblog.publishing.cms.ssh.SshCommandExecutor.SshConnectionParams;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SshjCommandExecutorのテスト(issue #1814)。sshjでSSH/SFTPを実際に話す実装なので、モックではなく、
 * 同一JVM内で起動したApache MINA SSHDのサーバー(外部のSSHサーバーのサーバー側フェイク)に
 * 対して接続し、接続の再利用・ホスト鍵の検証・再接続・SFTPの入出力・アイドル接続の整理を検証する。
 */
class SshjCommandExecutorTest {

    private static final String USER = "deploy";

    @TempDir
    Path sftpRoot;

    @TempDir
    Path keyDir;

    private SshServer server;
    private KeyPair clientKey;
    private String clientKeyPem;
    private SshjCommandExecutor executor;
    private final AtomicInteger dropCounter = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        clientKey = generator.generateKeyPair();
        clientKeyPem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                        .encodeToString(clientKey.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";

        server = SshServer.setUpDefaultServer();
        server.setPort(0);
        SimpleGeneratorHostKeyProvider hostKeys = new SimpleGeneratorHostKeyProvider(keyDir.resolve("host.key"));
        hostKeys.setAlgorithm("RSA");
        hostKeys.setKeySize(2048);
        server.setKeyPairProvider(hostKeys);
        server.setPublickeyAuthenticator((user, key, session) ->
                USER.equals(user) && KeyUtils.compareKeys(key, clientKey.getPublic()));
        server.setFileSystemFactory(new VirtualFileSystemFactory(sftpRoot));
        server.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
        server.setCommandFactory((channel, command) -> new ScriptedCommand(command, dropCounter));
        server.start();

        executor = new SshjCommandExecutor();
    }

    @AfterEach
    void tearDown() throws Exception {
        executor.closeAllConnections();
        server.stop(true);
    }

    private SshConnectionParams params() {
        return params(USER, null);
    }

    private SshConnectionParams params(String user, String pinnedFingerprint) {
        return new SshConnectionParams("127.0.0.1", server.getPort(), user, clientKeyPem, pinnedFingerprint);
    }

    /** 外部のコマンドを実行する代わりに、コマンド文字列で決まる固定の応答を返すサーバー側のコマンド。 */
    private static final class ScriptedCommand implements Command {
        private final String command;
        private final AtomicInteger dropCounter;
        private InputStream in;
        private OutputStream out;
        private OutputStream err;
        private ExitCallback callback;

        ScriptedCommand(String command, AtomicInteger dropCounter) {
            this.command = command;
            this.dropCounter = dropCounter;
        }

        @Override
        public void setInputStream(InputStream in) {
            this.in = in;
        }

        @Override
        public void setOutputStream(OutputStream out) {
            this.out = out;
        }

        @Override
        public void setErrorStream(OutputStream err) {
            this.err = err;
        }

        @Override
        public void setExitCallback(ExitCallback callback) {
            this.callback = callback;
        }

        @Override
        public void start(ChannelSession channel, Environment env) {
            Thread thread = new Thread(() -> {
                try {
                    run(channel);
                } catch (IOException e) {
                    callback.onExit(255, e.getMessage());
                }
            });
            thread.setDaemon(true);
            thread.start();
        }

        private void run(ChannelSession channel) throws IOException {
            switch (command) {
                case "stdin" -> {
                    out.write(in.readAllBytes());
                    out.flush();
                    callback.onExit(0);
                }
                case "fail" -> {
                    err.write("boom".getBytes(StandardCharsets.UTF_8));
                    err.flush();
                    callback.onExit(3);
                }
                case "no-exit-status" -> {
                    out.write("partial".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    channel.close(false);
                }
                case "drop-once" -> {
                    if (dropCounter.getAndIncrement() == 0) {
                        channel.getSession().close(true);
                    } else {
                        out.write("recovered".getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        callback.onExit(0);
                    }
                }
                case "drop-always" -> channel.getSession().close(true);
                default -> {
                    out.write((command + "\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    callback.onExit(0);
                }
            }
        }

        @Override
        public void destroy(ChannelSession channel) {
            // 後始末の必要なリソースは無い
        }
    }

    // ---- exec / execAll ----

    @Test
    void exec_標準出力と終了ステータスとホスト鍵のfingerprintを返し_接続を再利用する() throws Exception {
        SshCommandResult first = executor.exec(params(), "hello", null);
        SshCommandResult second = executor.exec(params(), "world", null);

        assertEquals(0, first.exitStatus());
        assertEquals("hello\n", first.stdout());
        assertEquals("world\n", second.stdout());
        assertEquals("", first.stderr());
        // 接続確立時に捕捉したホスト鍵のfingerprint(sshjのSecurityUtils形式: 16進をコロンで区切ったもの)が結果に載る
        assertNotNull(first.observedHostKeyFingerprint());
        assertTrue(first.observedHostKeyFingerprint().matches("([0-9a-f]{2}:)+[0-9a-f]{2}"),
                first.observedHostKeyFingerprint());
        assertEquals(first.observedHostKeyFingerprint(), second.observedHostKeyFingerprint());
        assertEquals(1, server.getActiveSessions().size(), "2回の実行で接続は1本だけ");
    }

    @Test
    void exec_失敗したコマンドは終了ステータスとstderrを返す() {
        SshCommandResult result = executor.exec(params(), "fail", null);

        assertEquals(3, result.exitStatus());
        assertEquals("boom", result.stderr());
        assertFalse(result.ok());
    }

    @Test
    void exec_標準入力を渡すとコマンドの標準入力へ届く() {
        SshCommandResult result = executor.exec(params(), "stdin", "本文<p>x</p>".getBytes(StandardCharsets.UTF_8));

        assertEquals("本文<p>x</p>", result.stdout());
    }

    @Test
    void exec_終了ステータスを送らずにチャネルが閉じたら_1を返す() {
        SshCommandResult result = executor.exec(params(), "no-exit-status", null);

        assertEquals(-1, result.exitStatus());
        assertEquals("partial", result.stdout());
    }

    @Test
    void execAll_複数コマンドを順番に1本の接続で実行する() {
        List<SshCommandResult> results = executor.execAll(params(), List.of("one", "two", "three"));

        assertEquals(List.of("one\n", "two\n", "three\n"), results.stream().map(SshCommandResult::stdout).toList());
        assertEquals(1, server.getActiveSessions().size());
    }

    // ---- 接続の確立・検証 ----

    @Test
    void ホスト鍵のfingerprintが未設定または空白なら初回接続として受け入れる() {
        assertEquals(0, executor.exec(params(USER, null), "a", null).exitStatus());

        SshjCommandExecutor other = new SshjCommandExecutor();
        try {
            assertEquals(0, other.exec(params(USER, "  "), "b", null).exitStatus());
        } finally {
            other.closeAllConnections();
        }
    }

    @Test
    void ホスト鍵のfingerprintが一致すれば接続でき_一致しなければ接続に失敗する() {
        executor.exec(params(), "a", null);
        String observed = executor.exec(params(), "b", null).observedHostKeyFingerprint();

        SshjCommandExecutor pinned = new SshjCommandExecutor();
        SshjCommandExecutor mismatched = new SshjCommandExecutor();
        try {
            assertEquals(0, pinned.exec(params(USER, observed), "c", null).exitStatus());
            SshOperationException e = assertThrows(SshOperationException.class,
                    () -> mismatched.exec(params(USER, "SHA256:other"), "d", null));
            assertTrue(e.getMessage().startsWith("SSH接続に失敗しました (host=127.0.0.1)"), e.getMessage());
        } finally {
            pinned.closeAllConnections();
            mismatched.closeAllConnections();
        }
    }

    @Test
    void 認証に失敗したら接続失敗の例外() {
        SshOperationException e = assertThrows(SshOperationException.class,
                () -> executor.exec(params("intruder", null), "a", null));

        assertTrue(e.getMessage().startsWith("SSH接続に失敗しました"), e.getMessage());
    }

    @Test
    void 接続できないポートなら接続失敗の例外() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }

        SshOperationException e = assertThrows(SshOperationException.class, () -> executor.exec(
                new SshConnectionParams("127.0.0.1", closedPort, USER, clientKeyPem, null), "a", null));

        assertTrue(e.getMessage().startsWith("SSH接続に失敗しました"), e.getMessage());
    }

    @Test
    void 秘密鍵として読めない内容なら接続失敗の例外() {
        SshOperationException e = assertThrows(SshOperationException.class, () -> executor.exec(
                new SshConnectionParams("127.0.0.1", server.getPort(), USER, "not a key", null), "a", null));

        assertTrue(e.getMessage().startsWith("SSH接続に失敗しました"), e.getMessage());
    }

    // ---- 再接続 ----

    @Test
    void 接続が切れていれば次の実行で自動的に再接続する() throws Exception {
        executor.exec(params(), "a", null);
        server.getActiveSessions().forEach(session -> session.close(true));
        Thread.sleep(300);

        SshCommandResult result = executor.exec(params(), "again", null);

        assertEquals("again\n", result.stdout());
    }

    @Test
    void 実行中に接続が落ちたら1回だけ再接続して再実行する() {
        SshCommandResult result = executor.exec(params(), "drop-once", null);

        assertEquals("recovered", result.stdout());
        assertEquals(2, dropCounter.get());
    }

    @Test
    void 再接続しても落ちるなら実行失敗の例外() {
        SshOperationException e = assertThrows(SshOperationException.class,
                () -> executor.exec(params(), "drop-always", null));

        assertTrue(e.getMessage().startsWith("SSHコマンド実行に失敗しました (host=127.0.0.1)"), e.getMessage());
    }

    // ---- SFTP ----

    @Test
    void putFile_getFile_removeFileでリモートのファイルを転送_取得_削除する() throws Exception {
        byte[] data = "tarball-bytes".getBytes(StandardCharsets.UTF_8);

        executor.putFile(params(), data, "/upload.bin");

        assertArrayEquals(data, Files.readAllBytes(sftpRoot.resolve("upload.bin")));
        assertArrayEquals(data, executor.getFile(params(), "/upload.bin"));

        executor.removeFile(params(), "/upload.bin");

        assertFalse(Files.exists(sftpRoot.resolve("upload.bin")));
    }

    @Test
    void putFile_ディレクトリ無しのパス指定でも名前だけで転送できる() throws Exception {
        executor.putFile(params(), new byte[]{1, 2}, "bare.bin");

        assertTrue(Files.exists(sftpRoot.resolve("bare.bin")));
    }

    @Test
    void putFile_転送先が存在しなければ例外() {
        SshOperationException e = assertThrows(SshOperationException.class,
                () -> executor.putFile(params(), new byte[]{1}, "/no/such/dir/x.bin"));

        assertTrue(e.getMessage().startsWith("SFTP転送に失敗しました (host=127.0.0.1, path=/no/such/dir/x.bin)"), e.getMessage());
    }

    @Test
    void getFile_ファイルが無ければ例外() {
        SshOperationException e = assertThrows(SshOperationException.class,
                () -> executor.getFile(params(), "/missing.bin"));

        assertTrue(e.getMessage().startsWith("SFTPダウンロードに失敗しました (host=127.0.0.1, path=/missing.bin)"), e.getMessage());
    }

    @Test
    void removeFile_ファイルが無くても例外を投げない() {
        executor.removeFile(params(), "/missing.bin");
    }

    // ---- アイドル接続の整理・終了処理 ----

    @SuppressWarnings("unchecked")
    private Map<String, Object> pool() throws Exception {
        Field field = SshjCommandExecutor.class.getDeclaredField("pool");
        field.setAccessible(true);
        return (Map<String, Object>) field.get(executor);
    }

    private void setLastUsed(Object connection, long millis) throws Exception {
        Field field = connection.getClass().getDeclaredField("lastUsedAtMillis");
        field.setAccessible(true);
        field.setLong(connection, millis);
    }

    @Test
    void evictIdleConnections_使われたばかりの接続は閉じない() throws Exception {
        executor.exec(params(), "a", null);

        executor.evictIdleConnections();

        assertEquals(1, server.getActiveSessions().size());
    }

    @Test
    void evictIdleConnections_5分以上使われていない接続は閉じ_次の実行で再接続する() throws Exception {
        executor.exec(params(), "a", null);
        setLastUsed(pool().values().iterator().next(), 0L);

        executor.evictIdleConnections();
        waitUntil(() -> server.getActiveSessions().isEmpty());

        assertEquals("b\n", executor.exec(params(), "b", null).stdout());
        // すでに閉じた(clientがnullの)接続は、続けて整理しても何もしない
        setLastUsed(pool().values().iterator().next(), 0L);
        executor.evictIdleConnections();
        executor.evictIdleConnections();
    }

    @Test
    void evictIdleConnections_実行中でロックが取れない接続は閉じずに次回へ回す() throws Exception {
        executor.exec(params(), "a", null);
        Object connection = pool().values().iterator().next();
        setLastUsed(connection, 0L);
        Field lockField = connection.getClass().getDeclaredField("lock");
        lockField.setAccessible(true);
        java.util.concurrent.locks.ReentrantLock lock = (java.util.concurrent.locks.ReentrantLock) lockField.get(connection);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            lock.lock();
            try {
                locked.countDown();
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        });
        holder.start();
        assertTrue(locked.await(5, TimeUnit.SECONDS));
        try {
            executor.evictIdleConnections();

            assertEquals(1, server.getActiveSessions().size());
        } finally {
            release.countDown();
            holder.join();
        }
    }

    @Test
    void closeAllConnections_すべての接続を閉じる() throws Exception {
        executor.exec(params(), "a", null);

        executor.closeAllConnections();
        waitUntil(() -> server.getActiveSessions().isEmpty());

        assertTrue(server.getActiveSessions().isEmpty());
        executor.closeAllConnections();
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
    }
}
