package com.letsblog.api.ai;

/**
 * チェックポイントダウンロードの進捗。totalBytesが-1の場合はサーバーがContent-Lengthを返していない。
 */
public record DownloadProgress(long bytesDownloaded, long totalBytes) {
}
