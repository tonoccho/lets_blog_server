package com.letsblog.publishing.integration;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * {@code multipart/form-data}のリクエストボディを生のバイト列として組み立てるテスト専用ヘルパー
 * (issue #1061)。
 *
 * <p><b>なぜ手組みなのか。</b>本Issueが検証したいのはTomcatのmultipartパーサが実際に適用する
 * サイズ上限({@code spring.servlet.multipart.max-file-size}/{@code max-request-size})である。
 * {@code MockMvc}の{@code multipart()}は{@code MockMultipartHttpServletRequest}を
 * <b>パース済みの状態で</b>組み立てるため、Tomcatのパーサを一切通らず、上限超過そのものを
 * 再現できない。したがって{@code webEnvironment = RANDOM_PORT}で実サーブレットコンテナを
 * 起動し、JDKの{@link java.net.http.HttpClient}で本物のHTTPリクエストを送る必要がある。
 *
 * <p>{@code TestRestTemplate}は使わない。Spring Boot 4.1.0のモジュール分割により
 * {@code spring-boot-test}には含まれておらず、本サービスのテスト依存には無いため
 * (依存追加は本Issueのスコープ外)。
 */
final class MultipartBodyBuilder {

    private static final byte[] CRLF = "\r\n".getBytes(StandardCharsets.UTF_8);

    private final String boundary = "lbsTestBoundary" + UUID.randomUUID();
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    MultipartBodyBuilder field(String name, String value) {
        write("--" + boundary);
        write("Content-Disposition: form-data; name=\"" + name + "\"");
        write("");
        write(value);
        return this;
    }

    MultipartBodyBuilder file(String name, String filename, String contentType, byte[] content) {
        write("--" + boundary);
        write("Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + filename + "\"");
        write("Content-Type: " + contentType);
        write("");
        writeBytes(content);
        writeBytes(CRLF);
        return this;
    }

    String contentType() {
        return "multipart/form-data; boundary=" + boundary;
    }

    byte[] build() {
        final ByteArrayOutputStream complete = new ByteArrayOutputStream();
        writeBytes(complete, out.toByteArray());
        writeBytes(complete, ("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return complete.toByteArray();
    }

    private void write(String line) {
        writeBytes(line.getBytes(StandardCharsets.UTF_8));
        writeBytes(CRLF);
    }

    private void writeBytes(byte[] bytes) {
        writeBytes(out, bytes);
    }

    private static void writeBytes(ByteArrayOutputStream target, byte[] bytes) {
        try {
            target.write(bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
