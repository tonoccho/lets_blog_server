package com.letsblog.content.dto;

/**
 * WordPress の letsblog プラグインへ送る内容(issue #1558)。
 *
 * @param payload 送る内容(JSON文字列。プラグインへはこの文字列をそのまま渡す)
 * @param hash    {@code payload}のUTF-8バイト列のSHA-256(16進小文字)。プラグインの {@code status} の
 *                {@code sync_hash} と照合する
 */
public record LetsblogSyncPayloadResponse(String payload, String hash) {
}
