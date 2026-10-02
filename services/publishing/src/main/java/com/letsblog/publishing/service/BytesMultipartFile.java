package com.letsblog.publishing.service;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Objects;
import org.springframework.web.multipart.MultipartFile;

/**
 * バイト列を包んだ{@link MultipartFile}(issue #1341)。{@code PostPublishCommand}は
 * {@code List<MultipartFile>}を取るが、レビューではGitHubから取得したバイト列を直接持っており、
 * HTTPのマルチパートを経由しない。{@code MockMultipartFile}はtestスコープなので、本番側に用意する。
 *
 * <p>{@code originalFilename}にはパス区切りを含む参照文字列(例: {@code assets/cover.png})をそのまま渡せる
 * (HTTPを経由しないので、コンテナ側でベース名に変換されることはない)。
 */
public final class BytesMultipartFile implements MultipartFile {

    private final String name;
    private final String originalFilename;
    private final String contentType;
    private final byte[] bytes;

    public BytesMultipartFile(String name, String originalFilename, String contentType, byte[] bytes) {
        this.name = name;
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.bytes = Objects.requireNonNull(bytes, "bytes").clone();
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getOriginalFilename() {
        return originalFilename;
    }

    @Override
    public String getContentType() {
        return contentType;
    }

    @Override
    public boolean isEmpty() {
        return bytes.length == 0;
    }

    @Override
    public long getSize() {
        return bytes.length;
    }

    @Override
    public byte[] getBytes() {
        return bytes.clone();
    }

    @Override
    public InputStream getInputStream() {
        return new ByteArrayInputStream(bytes);
    }

    @Override
    public void transferTo(File dest) throws IOException {
        Files.write(dest.toPath(), bytes);
    }
}
