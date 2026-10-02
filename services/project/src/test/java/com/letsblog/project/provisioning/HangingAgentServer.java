package com.letsblog.project.provisioning;

import java.io.IOException;
import java.net.ServerSocket;

/** 接続は受け付けるが応答を一切返さない(無応答のprovision-agentを模す)テスト用サーバー。 */
final class HangingAgentServer implements AutoCloseable {

    private final ServerSocket socket;

    HangingAgentServer() throws IOException {
        this.socket = new ServerSocket(0);
    }

    String baseUrl() {
        return "http://localhost:" + socket.getLocalPort();
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
