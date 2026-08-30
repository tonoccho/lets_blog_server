package com.letsblog.media.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * ComfyUIと共有するチェックポイント格納ディレクトリ(app.comfyui-models-path配下のcheckpoints/)への
 * ファイルダウンロード・削除を行う。ComfyUI自体には標準の追加/削除APIが無いため、
 * APIサーバーが共有ボリュームへ直接ファイルを配置/削除する。
 */
@Service
public class ComfyUiCheckpointStorageService {

    private static final Pattern SAFE_FILE_NAME = Pattern.compile("^[A-Za-z0-9_.-]+$");
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(30);

    private final Path checkpointsDir;
    private final HttpClient httpClient;

    public ComfyUiCheckpointStorageService(@Value("${app.comfyui-models-path}") String modelsPath) {
        this.checkpointsDir = Path.of(modelsPath, "checkpoints");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * 指定URLからチェックポイントファイルをダウンロードし、共有ボリュームに配置する。
     * ダウンロード完了前は".part"拡張子で書き込み、完了後にリネームすることで、
     * 中断時に不完全なファイルが一覧に「インストール済み」として現れないようにする。
     * onProgressには受信バイト数/Content-Length(不明な場合は-1)を随時通知する。
     */
    public void downloadCheckpoint(String url, String fileName, Consumer<DownloadProgress> onProgress) {
        String safeFileName = requireSafeFileName(fileName);
        try {
            Files.createDirectories(checkpointsDir);
            Path partFile = checkpointsDir.resolve(safeFileName + ".part");
            Path targetFile = checkpointsDir.resolve(safeFileName);

            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(DOWNLOAD_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                throw new AiServiceException(
                        "チェックポイントのダウンロードに失敗しました: HTTP " + response.statusCode() + " (" + url + ")", null);
            }
            long totalBytes = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            long bytesDownloaded = 0;
            long lastReportedAt = System.currentTimeMillis();
            try (InputStream in = response.body();
                    OutputStream out = Files.newOutputStream(
                            partFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    bytesDownloaded += read;
                    long now = System.currentTimeMillis();
                    if (now - lastReportedAt >= 500) {
                        onProgress.accept(new DownloadProgress(bytesDownloaded, totalBytes));
                        lastReportedAt = now;
                    }
                }
            }
            onProgress.accept(new DownloadProgress(bytesDownloaded, totalBytes));
            Files.move(partFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new AiServiceException("チェックポイントのダウンロード中にエラーが発生しました: " + e.getMessage(), e);
        }
    }

    public void deleteCheckpoint(String fileName) {
        String safeFileName = requireSafeFileName(fileName);
        try {
            Files.deleteIfExists(checkpointsDir.resolve(safeFileName));
        } catch (IOException e) {
            throw new AiServiceException("チェックポイントの削除に失敗しました: " + e.getMessage(), e);
        }
    }

    private String requireSafeFileName(String fileName) {
        if (fileName == null || fileName.isBlank() || !SAFE_FILE_NAME.matcher(fileName).matches()) {
            throw new IllegalArgumentException(
                    "ファイル名は英数字・アンダースコア・ハイフン・ピリオドのみ使用できます: " + fileName);
        }
        return fileName;
    }
}
