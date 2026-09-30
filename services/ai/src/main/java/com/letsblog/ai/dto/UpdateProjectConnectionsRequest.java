package com.letsblog.ai.dto;

/**
 * プロジェクト単位のOllama / ComfyUI接続先の更新(issue #1503)。項目を省略(null)すると変更せず、
 * 空文字を送るとその項目の上書きを解除する。
 */
public record UpdateProjectConnectionsRequest(String ollamaBaseUrl, String comfyuiBaseUrl) {
}
