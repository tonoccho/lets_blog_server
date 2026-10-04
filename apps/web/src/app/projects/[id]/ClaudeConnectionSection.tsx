"use client";

import { useEffect, useState } from "react";
import type { AiConnection } from "@/lib/apiClient";
import { clearClaudeApiKeyAction, fetchAiConnectionsAction, setClaudeApiKeyAction } from "./actions";

const CLAUDE_CONSOLE_URL = "https://platform.claude.com/settings/keys";

// APIキーはプロジェクト単位だけ(issue #1568)。出所は「プロジェクト設定」か「未設定」のどちらか。
const SOURCE_LABEL: Record<"PROJECT" | "NONE", string> = {
  PROJECT: "プロジェクト設定",
  NONE: "未設定",
};

function errorText(err: unknown): string {
  return err instanceof Error ? err.message : String(err);
}

/**
 * Claude(Anthropic)の接続情報(issue #1507)。
 *
 * 状態と設定の出所は #1499 の ai-connections の CLAUDE 行から表示する。Anthropic にはOAuthで第三者アプリが
 * APIを使う仕組みが無い(#1505)ため、コンソールへのリンクでAPIキーを発行してもらい、ここへ貼り付けて
 * このプロジェクト専用に保存する。キーの値は画面に出さない(送信後は入力欄も空にする)。
 */
export function ClaudeConnectionSection({ projectId }: { projectId: number }) {
  const [row, setRow] = useState<AiConnection | null>(null);
  const [loaded, setLoaded] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [apiKey, setApiKey] = useState("");
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ type: "error" | "success"; text: string } | null>(null);

  async function reload(): Promise<boolean> {
    try {
      const rows = await fetchAiConnectionsAction(projectId);
      setRow(rows.find((r) => r.provider === "CLAUDE") ?? null);
      setLoadError(null);
      return true;
    } catch (err) {
      setLoadError(`接続状態の取得に失敗しました: ${errorText(err)}`);
      return false;
    } finally {
      setLoaded(true);
    }
  }

  useEffect(() => {
    let cancelled = false;
    fetchAiConnectionsAction(projectId).then(
      (rows) => {
        if (cancelled) return;
        setRow(rows.find((r) => r.provider === "CLAUDE") ?? null);
        setLoaded(true);
      },
      (err) => {
        if (cancelled) return;
        setLoadError(`接続状態の取得に失敗しました: ${errorText(err)}`);
        setLoaded(true);
      }
    );
    return () => {
      cancelled = true;
    };
  }, [projectId]);

  async function handleConnect(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setMessage(null);
    if (!apiKey.trim()) {
      setMessage({ type: "error", text: "APIキーを入力してください。" });
      return;
    }
    setBusy(true);
    const result = await setClaudeApiKeyAction(projectId, apiKey.trim());
    if (result.error !== undefined) {
      // 入力値(apiKey)はそのまま残す
      setBusy(false);
      setMessage({ type: "error", text: result.error });
      return;
    }
    setApiKey("");
    await reload();
    setBusy(false);
    setMessage({ type: "success", text: "接続しました。" });
  }

  async function handleDisconnect() {
    setMessage(null);
    setBusy(true);
    const result = await clearClaudeApiKeyAction(projectId);
    if (result.error !== undefined) {
      setBusy(false);
      setMessage({ type: "error", text: result.error });
      return;
    }
    await reload();
    setBusy(false);
    setMessage({ type: "success", text: "接続を解除しました。" });
  }

  const projectKeyStored = row?.source === "PROJECT";
  const source = projectKeyStored ? "PROJECT" : "NONE";

  return (
    <section className="space-y-2 rounded border border-neutral-200 dark:border-neutral-800 p-3 text-sm">
      <h4 className="font-medium text-neutral-700 dark:text-neutral-300">Claudeの接続情報</h4>

      {loadError && (
        <p role="alert" className="text-red-600">
          {loadError}
        </p>
      )}
      {loaded && !loadError && (
        <dl className="grid grid-cols-[8rem_1fr] gap-x-2 gap-y-1">
          <dt className="text-neutral-500 dark:text-neutral-400">接続状態</dt>
          <dd>{projectKeyStored ? "接続済み" : "未接続"}</dd>
          <dt className="text-neutral-500 dark:text-neutral-400">設定の出所</dt>
          <dd>{SOURCE_LABEL[source]}</dd>
        </dl>
      )}

      <p className="text-neutral-600 dark:text-neutral-400">
        AnthropicのコンソールでAPIキーを発行し、下の欄に貼り付けて接続します。
        <a
          href={CLAUDE_CONSOLE_URL}
          target="_blank"
          rel="noopener noreferrer"
          className="ml-1 text-blue-600 underline"
        >
          キーを発行する
        </a>
      </p>
      <p className="text-xs text-neutral-500 dark:text-neutral-400">
        APIキーは従量課金で、Claude のサブスクリプションとは別契約です。
      </p>

      {/* JS無効時のネイティブGETフォールバックでキーがURLへ漏れないよう method="post" を明示する(#1051と同様) */}
      <form onSubmit={handleConnect} method="post" className="flex flex-wrap items-end gap-2">
        <label className="flex flex-col gap-1">
          <span className="text-neutral-600 dark:text-neutral-400">Anthropic APIキー</span>
          <input
            type="password"
            value={apiKey}
            onChange={(e) => setApiKey(e.target.value)}
            autoComplete="off"
            className="w-96 max-w-full rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm"
          />
        </label>
        <button
          type="submit"
          disabled={busy}
          className="rounded bg-neutral-900 px-3 py-2 text-white disabled:bg-neutral-200 disabled:text-neutral-600"
        >
          接続
        </button>
        {projectKeyStored && (
          <button
            type="button"
            onClick={handleDisconnect}
            disabled={busy}
            className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-red-600 disabled:text-neutral-400"
          >
            接続を解除
          </button>
        )}
        <p className="w-full text-xs text-neutral-500 dark:text-neutral-400">
          このプロジェクトのLLM生成(Claude)でだけ使われます。解除するとこのプロジェクトでは使えなくなります。
        </p>
      </form>
      {message && (
        <p
          role={message.type === "error" ? "alert" : "status"}
          className={message.type === "error" ? "text-red-600" : "text-green-600"}
        >
          {message.text}
        </p>
      )}
    </section>
  );
}
