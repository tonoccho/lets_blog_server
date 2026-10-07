package com.letsblog.ai.dto;

/** Ollamaのモデルをpull(インストール)するリクエスト(issue #1675)。形式の検証はサービス側で行う。 */
public record PullOllamaModelRequest(String model) {
}
