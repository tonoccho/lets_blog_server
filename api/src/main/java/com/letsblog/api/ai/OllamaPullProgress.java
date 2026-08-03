package com.letsblog.api.ai;

/**
 * Ollamaの/api/pull(stream:true)から届く1行分の進捗情報。
 * totalが0の場合はサーバー側がまだサイズを提示していない(マニフェスト取得中等)。
 */
public record OllamaPullProgress(String status, long total, long completed) {
}
