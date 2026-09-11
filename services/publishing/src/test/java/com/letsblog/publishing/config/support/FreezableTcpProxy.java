package com.letsblog.publishing.config.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * issue #1095: 「mysqlコンテナを再作成すると、その時点で確立済みだったTCP接続だけが
 * サイレントに(FIN/RSTを送らずに)応答不能になる」状況を、実際にコンテナを作り直すことなく
 * 決定的に再現するためのテスト専用TCPリレー。
 *
 * <p>{@code freezeExistingConnections()}を呼ぶと、その時点で確立済みの各接続について
 * バイトの中継だけを止める(ソケット自体は閉じない)。これにより、相手には
 * FIN/RSTが一切届かず、読み取り待ちのスレッドは(ソケットタイムアウトが設定されていない限り)
 * 無期限にブロックする——mysqlコンテナ再作成時に発生する現象そのものを模す。
 *
 * <p>新規接続はfreeze後も引き続き受け付け、実バックエンド(使い捨てのMySQLコンテナ、
 * まだ動き続けている)へ正常に中継する。これは「古い接続だけが死に、新規接続は
 * 再作成後のmysqlへ正常につながる」という実際の障害の非対称性を再現するためである。
 */
public final class FreezableTcpProxy implements AutoCloseable {

    private final String targetHost;
    private final int targetPort;
    private final ServerSocket serverSocket;
    private final Thread acceptThread;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final CopyOnWriteArrayList<Relay> relays = new CopyOnWriteArrayList<>();
    private final AtomicInteger acceptedConnections = new AtomicInteger(0);

    public FreezableTcpProxy(String targetHost, int targetPort) throws IOException {
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        this.serverSocket = new ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress());
        this.acceptThread = new Thread(this::acceptLoop, "freezable-tcp-proxy-accept");
        this.acceptThread.setDaemon(true);
        this.acceptThread.start();
    }

    public int getLocalPort() {
        return serverSocket.getLocalPort();
    }

    public int getAcceptedConnectionCount() {
        return acceptedConnections.get();
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket client = serverSocket.accept();
                acceptedConnections.incrementAndGet();
                Socket backend = new Socket(targetHost, targetPort);
                Relay relay = new Relay(client, backend);
                relays.add(relay);
                relay.start();
            } catch (IOException e) {
                if (running.get()) {
                    // serverSocket.close()によるacceptの中断以外は想定しないため、ここでは無視する。
                    // (テスト用ヘルパーであり、本番のエラーハンドリング方針は問わない)
                    continue;
                }
            }
        }
    }

    /** その時点で確立済みの全接続について、バイト中継を止める(ソケットは閉じない)。 */
    public void freezeExistingConnections() {
        for (Relay relay : relays) {
            relay.freeze();
        }
    }

    @Override
    public void close() {
        running.set(false);
        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // クローズ時の例外はテスト終了処理として無視してよい。
        }
        for (Relay relay : relays) {
            relay.forceClose();
        }
    }

    /** クライアント⇄バックエンド間の双方向バイト中継。freeze()されると中継を止める。 */
    private static final class Relay {
        private final Socket client;
        private final Socket backend;
        private final AtomicBoolean frozen = new AtomicBoolean(false);
        private final Thread clientToBackend;
        private final Thread backendToClient;

        Relay(Socket client, Socket backend) {
            this.client = client;
            this.backend = backend;
            this.clientToBackend = new Thread(() -> pump(client, backend), "proxy-c2b");
            this.backendToClient = new Thread(() -> pump(backend, client), "proxy-b2c");
            this.clientToBackend.setDaemon(true);
            this.backendToClient.setDaemon(true);
        }

        void start() {
            clientToBackend.start();
            backendToClient.start();
        }

        void freeze() {
            frozen.set(true);
        }

        private void pump(Socket from, Socket to) {
            try {
                InputStream in = from.getInputStream();
                OutputStream out = to.getOutputStream();
                byte[] buffer = new byte[8192];
                while (!Thread.currentThread().isInterrupted()) {
                    if (frozen.get()) {
                        // ブロックせず定期的にfrozen状態を再チェックするだけで、
                        // 実ソケットの読み書きは一切行わない(=バイトを届けない)。
                        Thread.sleep(50);
                        continue;
                    }
                    int read = in.read(buffer);
                    if (read < 0) {
                        return;
                    }
                    if (frozen.get()) {
                        // freeze()がread()中に発生した場合、読み取ってしまったバイトも
                        // 転送しない(=中継停止後は一切データが届かないようにする)。
                        continue;
                    }
                    out.write(buffer, 0, read);
                    out.flush();
                }
            } catch (IOException | InterruptedException ignored) {
                // 相手側クローズ・強制終了時の例外はテスト用リレーとして無視してよい。
            }
        }

        void forceClose() {
            clientToBackend.interrupt();
            backendToClient.interrupt();
            closeQuietly(client);
            closeQuietly(backend);
        }

        private static void closeQuietly(Socket socket) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // クローズ失敗はテスト終了処理として無視してよい。
            }
        }
    }
}
