package com.letsblog.publishing.config.support;

import java.io.IOException;
import java.net.ServerSocket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * issue #1095: HikariCP設定の回復性を検証するために、テストのためだけに使い捨てる独立した
 * MySQLコンテナを起動する。
 *
 * <p>ADR-0006は「サービスのDBテストはTestcontainersを使わず、共有の実MySQLに接続する」と
 * 決めているが、それはservices/*配下の通常のドメインロジックのテストの話であり、本クラスが
 * 対象にしているのは全く別の懸念(HikariCP自体の障害復旧の検証)である。共有の実MySQL
 * (docker-compose.yml の {@code mysql} サービス、コンテナ名 {@code lbs-mysql})は現在稼働中の
 * 依存JVMサービス群が接続している本番相当のスタックであり、本Issueが扱う障害そのもの
 * (mysql再作成で依存サービスのプールが固まる)を招く再作成は、このテストのために踏んでは
 * ならない。そのため、{@code lbs-mysql}とは無関係の、ランダムなコンテナ名・ランダムな
 * ホストポートを使う使い捨てコンテナをここで都度起動・破棄する。
 */
public final class DisposableMysqlContainer implements AutoCloseable {

    private static final String IMAGE = "mysql:8.0";
    public static final String DATABASE = "hikari_test";
    public static final String USERNAME = "hikari_test";
    public static final String PASSWORD = "hikari_test";

    private final String containerName;
    private final int hostPort;

    private DisposableMysqlContainer(String containerName, int hostPort) {
        this.containerName = containerName;
        this.hostPort = hostPort;
    }

    public static DisposableMysqlContainer startAndWaitUntilReady() throws Exception {
        String name = "lbs-hikari-test-" + Long.toHexString(System.nanoTime());
        int port = findFreeLoopbackPort();
        run("docker", "run", "-d", "--name", name,
                "-p", "127.0.0.1:" + port + ":3306",
                "-e", "MYSQL_ROOT_PASSWORD=root_test",
                "-e", "MYSQL_DATABASE=" + DATABASE,
                "-e", "MYSQL_USER=" + USERNAME,
                "-e", "MYSQL_PASSWORD=" + PASSWORD,
                IMAGE);
        DisposableMysqlContainer container = new DisposableMysqlContainer(name, port);
        container.waitUntilAcceptingConnections(Duration.ofSeconds(90));
        return container;
    }

    public int getHostPort() {
        return hostPort;
    }

    private void waitUntilAcceptingConnections(Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        SQLException lastFailure = null;
        while (Instant.now().isBefore(deadline)) {
            try (Connection connection = DriverManager.getConnection(jdbcUrl(), USERNAME, PASSWORD)) {
                connection.createStatement().execute("SELECT 1");
                return;
            } catch (SQLException e) {
                lastFailure = e;
                TimeUnit.MILLISECONDS.sleep(500);
            }
        }
        throw new IllegalStateException(
                "使い捨てMySQLコンテナ(" + containerName + ")が起動時間内に応答可能にならなかった",
                lastFailure);
    }

    public String jdbcUrl() {
        return "jdbc:mysql://127.0.0.1:" + hostPort + "/" + DATABASE
                + "?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true";
    }

    private static int findFreeLoopbackPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static void run(String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IllegalStateException("コマンド失敗(" + exit + "): " + String.join(" ", command)
                    + "\n" + output);
        }
    }

    @Override
    public void close() throws Exception {
        run("docker", "rm", "-f", containerName);
    }
}
