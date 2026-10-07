"use client";

import { useState } from "react";
import type { GenerationJobDetail } from "@/lib/apiClient";
import { pullOllamaModelAction } from "./actions";
import { useGenerationJobPolling } from "./useGenerationJobPolling";
import { formatJobProgress, parseJobProgress, type JobProgress } from "./jobProgress";

/** Ollamaのモデル名(`name[:tag]`。`hf.co/user/repo:Q4_K_M` のような名前空間つきも含む)。サーバ側の検証と同じ形。 */
const MODEL_NAME = /^[A-Za-z0-9][A-Za-z0-9._/-]*(:[A-Za-z0-9._-]+)?$/;
const MODEL_NAME_ERROR = "モデル名の形式が不正です(例: qwen2.5:7b-instruct)。英数字と . _ - / と、タグの区切りの : だけが使えます。";

/**
 * Ollamaの接続セクション(AiConnectionSection)に置く、モデルのインストール(pull)フォーム(issue #1675)。
 * 開始はジョブを作ってすぐ返り、進捗はジョブのポーリング(ComfyUIのチェックポイント導入と同じ
 * useGenerationJobPolling / jobProgress)で表示する。完了も失敗(理由つき)も画面に出す。
 * 同じモデルがすでに実行中のときは、新しく始めず実行中であることを示してそのジョブの進捗を表示する。
 */
export function OllamaModelPullForm({ projectId }: { projectId: number }) {
  const [model, setModel] = useState("");
  const [pulling, setPulling] = useState<string | null>(null);
  const [progress, setProgress] = useState<JobProgress | null>(null);
  const [message, setMessage] = useState<{ type: "error" | "success" | "info"; text: string } | null>(null);

  const { startPolling } = useGenerationJobPolling(handleSettled, (job) =>
    setProgress(parseJobProgress(job.resultPayload))
  );

  function handleSettled(job: GenerationJobDetail) {
    const name = pulling;
    setPulling(null);
    setProgress(null);
    if (job.status === "done") {
      setMessage({ type: "success", text: `「${name}」のインストールが完了しました。` });
      setModel("");
    } else {
      const reason = extractError(job.resultPayload);
      setMessage({
        type: "error",
        text: reason ? `インストールに失敗しました: ${reason}` : "インストールに失敗しました(理由は不明です)。",
      });
    }
  }

  async function handleSubmit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const name = model.trim();
    if (!name) {
      setMessage({ type: "error", text: "モデル名を入力してください。" });
      return;
    }
    if (!MODEL_NAME.test(name)) {
      setMessage({ type: "error", text: MODEL_NAME_ERROR });
      return;
    }
    setMessage(null);
    setProgress(null);
    setPulling(name);
    const result = await pullOllamaModelAction(projectId, name);
    if (result.error !== undefined) {
      setPulling(null);
      setMessage({ type: "error", text: result.error });
      return;
    }
    if (result.alreadyRunning) {
      setMessage({ type: "info", text: "同じモデルのインストールがすでに実行中です。その進捗を表示します。" });
    }
    startPolling(result.jobId as number);
  }

  return (
    // JS無効時のネイティブGETフォールバックでモデル名がURLへ漏れないよう method="post" を明示する(#1051と同様)
    <form onSubmit={handleSubmit} method="post" className="flex flex-wrap items-end gap-2 border-t border-neutral-200 dark:border-neutral-800 pt-2">
      <label className="flex flex-col gap-1">
        <span className="text-neutral-600 dark:text-neutral-400">インストールするOllamaモデル名</span>
        <input
          value={model}
          onChange={(e) => setModel(e.target.value)}
          placeholder="qwen2.5:7b-instruct"
          disabled={pulling !== null}
          className="w-96 max-w-full rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm"
        />
      </label>
      <button
        type="submit"
        disabled={pulling !== null}
        className="rounded bg-neutral-900 px-3 py-2 text-white disabled:bg-neutral-200 disabled:text-neutral-600"
      >
        {pulling !== null ? "インストール中(数分かかる場合があります)…" : "インストール"}
      </button>
      <p className="w-full text-xs text-neutral-500 dark:text-neutral-400">
        このプロジェクトのOllama接続先へモデルを取得します。Ollamaが外部へ通信できる必要があります。
      </p>
      {pulling !== null && (
        <p role="status" className="w-full text-neutral-500 dark:text-neutral-400">
          {progress ? formatJobProgress(progress) : "開始しています…"}
        </p>
      )}
      {message && (
        <p
          role={message.type === "error" ? "alert" : "status"}
          className={`w-full ${message.type === "error" ? "text-red-600" : message.type === "success" ? "text-green-600" : "text-neutral-600 dark:text-neutral-400"}`}
        >
          {message.text}
        </p>
      )}
    </form>
  );
}

function extractError(resultPayload: string | null): string | null {
  if (!resultPayload) {
    return null;
  }
  try {
    const parsed = JSON.parse(resultPayload) as { error?: string };
    return parsed.error ?? null;
  } catch {
    return null;
  }
}
