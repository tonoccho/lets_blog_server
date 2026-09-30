"use client";

import { useEffect, useRef, useState } from "react";
import type { AiConnection, ConnectionSource, ProjectConnectionsResponse } from "@/lib/apiClient";
import { fetchAiConnectionsAction, fetchProjectConnectionsAction, updateProjectConnectionAction } from "./actions";

type Provider = "OLLAMA" | "COMFYUI";

const PROVIDER_LABEL: Record<Provider, string> = {
  OLLAMA: "Ollama",
  COMFYUI: "ComfyUI",
};

const SOURCE_LABEL: Record<ConnectionSource, string> = {
  PROJECT: "プロジェクト設定",
  DATABASE: "システム設定",
  ENVIRONMENT: "環境変数既定",
  NONE: "未設定",
};

const STATUS_LABEL: Record<AiConnection["status"], string> = {
  NORMAL: "利用可能",
  WARNING: "警告",
  ERROR: "利用不可",
};

function errorText(err: unknown): string {
  return err instanceof Error ? err.message : String(err);
}

/**
 * Ollama(LLMタブ)/ ComfyUI(画像生成タブ)の接続情報(issue #1504)。
 *
 * 接続先URLと出所(#1503のAPI)は速いので先に表示し、利用可否(#1499のAPI。疎通確認で最大数秒かかる)は
 * 別に取得して「確認中…」から差し替える。どちらもタブの表示時にクライアント側で取得し、失敗は
 * 空表示にせずエラーとして表示する。プロジェクト単位の接続先を保存でき、空で保存すると上書きを解除する。
 */
export function AiConnectionSection({ projectId, provider }: { projectId: number; provider: Provider }) {
  const label = PROVIDER_LABEL[provider];
  const [connections, setConnections] = useState<ProjectConnectionsResponse | null>(null);
  const [connectionsError, setConnectionsError] = useState<string | null>(null);
  const [statuses, setStatuses] = useState<AiConnection[] | null>(null);
  const [statusError, setStatusError] = useState<string | null>(null);
  const [url, setUrl] = useState("");
  // 取得が終わる前に入力を始めた場合、取得結果で入力中の値を潰さないための印
  const editedRef = useRef(false);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState<{ type: "error" | "success"; text: string } | null>(null);

  const entryOf = (data: ProjectConnectionsResponse | null) =>
    provider === "OLLAMA" ? data?.ollama : data?.comfyui;

  function applyStatus(result: StatusResult) {
    if ("error" in result) setStatusError(result.error);
    else setStatuses(result.statuses);
  }

  useEffect(() => {
    let cancelled = false;
    fetchProjectConnectionsAction(projectId).then(
      (data) => {
        if (cancelled) return;
        setConnections(data);
        if (!editedRef.current) {
          setUrl((provider === "OLLAMA" ? data.ollama : data.comfyui)?.overrideBaseUrl ?? "");
        }
      },
      (err) => {
        if (!cancelled) setConnectionsError(`接続先の取得に失敗しました: ${errorText(err)}`);
      }
    );
    checkStatus(projectId).then((result) => {
      if (cancelled) return;
      if ("error" in result) setStatusError(result.error);
      else setStatuses(result.statuses);
    });
    return () => {
      cancelled = true;
    };
  }, [projectId, provider]);

  async function handleSubmit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setSaving(true);
    setMessage(null);
    const result = await updateProjectConnectionAction(projectId, provider, url.trim());
    setSaving(false);
    if (result.error !== undefined) {
      // 入力値(url)はそのまま残す
      setMessage({ type: "error", text: result.error });
      return;
    }
    const data = result.data as ProjectConnectionsResponse;
    setConnections(data);
    setConnectionsError(null);
    setUrl(entryOf(data)?.overrideBaseUrl ?? "");
    setMessage({ type: "success", text: "保存しました。" });
    setStatuses(null);
    setStatusError(null);
    applyStatus(await checkStatus(projectId));
  }

  const entry = entryOf(connections);
  const status = statuses?.find((s) => s.provider === provider);

  return (
    <section className="space-y-2 rounded border border-neutral-200 dark:border-neutral-800 p-3 text-sm">
      <h4 className="font-medium text-neutral-700 dark:text-neutral-300">{label}の接続情報</h4>

      {connectionsError && (
        <p role="alert" className="text-red-600">
          {connectionsError}
        </p>
      )}
      {connections && (
        <dl className="grid grid-cols-[8rem_1fr] gap-x-2 gap-y-1">
          <dt className="text-neutral-500 dark:text-neutral-400">接続先URL</dt>
          <dd className="break-all">{entry?.baseUrl || "未設定"}</dd>
          <dt className="text-neutral-500 dark:text-neutral-400">設定の出所</dt>
          <dd>{SOURCE_LABEL[entry?.source ?? "NONE"]}</dd>
          <dt className="text-neutral-500 dark:text-neutral-400">利用可否</dt>
          <dd>{statusText(statuses, status, statusError)}</dd>
        </dl>
      )}
      {status?.detail && status.status !== "NORMAL" && (
        <p className="text-neutral-600 dark:text-neutral-400">{`理由: ${status.detail}`}</p>
      )}
      {statusError && (
        <p role="alert" className="text-red-600">
          {statusError}
        </p>
      )}

      {/* JS無効時のネイティブGETフォールバックで入力値がURLへ漏れないよう method="post" を明示する(#1051と同様) */}
      <form onSubmit={handleSubmit} method="post" className="flex flex-wrap items-end gap-2">
        <label className="flex flex-col gap-1">
          <span className="text-neutral-600 dark:text-neutral-400">{label}の接続先URL(このプロジェクトで上書き)</span>
          <input
            value={url}
            onChange={(e) => {
              editedRef.current = true;
              setUrl(e.target.value);
            }}
            className="w-96 max-w-full rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm"
          />
        </label>
        <button
          type="submit"
          disabled={saving}
          className="rounded bg-neutral-900 px-3 py-2 text-white disabled:bg-neutral-200 disabled:text-neutral-600"
        >
          {saving ? "保存中…" : "保存"}
        </button>
        <p className="w-full text-xs text-neutral-500 dark:text-neutral-400">
          空で保存すると上書きを解除し、システム設定または環境変数の既定値に戻ります。
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

type StatusResult = { statuses: AiConnection[] } | { error: string };

/** 利用可否を取得する。失敗は例外にせず、表示用のエラーとして返す(握り潰さない)。 */
async function checkStatus(projectId: number): Promise<StatusResult> {
  try {
    return { statuses: await fetchAiConnectionsAction(projectId) };
  } catch (err) {
    return { error: `利用可否の確認に失敗しました: ${errorText(err)}` };
  }
}

function statusText(statuses: AiConnection[] | null, status: AiConnection | undefined, statusError: string | null) {
  if (statusError) return "確認できませんでした";
  if (statuses === null) return "確認中…";
  return status ? STATUS_LABEL[status.status] : "利用可否を確認できませんでした。";
}
