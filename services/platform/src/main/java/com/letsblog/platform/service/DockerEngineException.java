package com.letsblog.platform.service;

/** Docker Engine API(docker-socket-proxy経由)の呼び出しに失敗した(issue #1399)。 */
public class DockerEngineException extends RuntimeException {

    public DockerEngineException(String message) {
        super(message);
    }

    public DockerEngineException(String message, Throwable cause) {
        super(message, cause);
    }
}
