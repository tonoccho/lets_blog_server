"use client";

import { useState } from "react";
import type { Project, ProjectEnvironment, Site, UnreferencedMediaItem, GenerationJobDetail } from "@/lib/apiClient";
import { fetchMediaGarbageScanAction, deleteMediaGarbageAction } from "./actions";
import { useGenerationJobPolling } from "./useGenerationJobPolling";
import { formatJobProgress, parseJobProgress, type JobProgress } from "./jobProgress";

const ENVIRONMENT_LABEL: Record<ProjectEnvironment, string> = {
  local: "ローカル",
  test: "テスト",
  production: "本番",
};

interface DeleteResultPayload {
  deletedCount: number;
  failedCount: number;
  deletedMediaIds: string[];
  failures: Record<string, string>;
}

function parseDeleteResult(resultPayload: string | null): DeleteResultPayload | null {
  if (!resultPayload) {
    return null;
  }
  try {
    const parsed = JSON.parse(resultPayload) as Record<string, unknown>;
    if (typeof parsed.deletedCount !== "number") {
      return null;
    }
    return {
      deletedCount: parsed.deletedCount,
      failedCount: typeof parsed.failedCount === "number" ? parsed.failedCount : 0,
      deletedMediaIds: Array.isArray(parsed.deletedMediaIds) ? (parsed.deletedMediaIds as string[]) : [],
      failures: (parsed.failures as Record<string, string>) ?? {},
    };
  } catch {
    return null;
  }
}

/**
 * 投稿本文・アイキャッチ・主要サイト設定のいずれからも参照されていないメディアを検出・削除する
 * ガベージコレクション画面(issue #500)。
 */
export function GarbageCollectionPanel({ projectId, project }: { projectId: number; project: Project }) {
  const sitesByEnvironment: Record<ProjectEnvironment, Site | null> = {
    local: project.localSite,
    test: project.testSite,
    production: project.productionSite,
  };

  const availableEnvironments = (["local", "test", "production"] as const)
    .filter((environment) => {
      const site = sitesByEnvironment[environment];
      return site?.managedWordpress || site?.sshConfigured;
    })
    .map((environment) => ({ value: environment, label: ENVIRONMENT_LABEL[environment] }));

  const [environment, setEnvironment] = useState<ProjectEnvironment | "">("");
  const [scanResult, setScanResult] = useState<{
    items: UnreferencedMediaItem[];
    totalMediaCount: number;
    referencedMediaCount: number;
  } | null>(null);
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [scanning, setScanning] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [progress, setProgress] = useState<JobProgress | null>(null);
  const [resultSummary, setResultSummary] = useState<DeleteResultPayload | null>(null);
  const [message, setMessage] = useState<{ type: "error" | "success"; text: string } | null>(null);

  async function runScan(env: ProjectEnvironment) {
    setScanning(true);
    setMessage(null);
    setResultSummary(null);
    setSelectedIds(new Set());
    const result = await fetchMediaGarbageScanAction(projectId, env);
    setScanning(false);
    if (result.error || !result.data) {
      setScanResult(null);
      setMessage({ type: "error", text: result.error ?? "スキャンに失敗しました。" });
      return;
    }
    setScanResult({
      items: result.data.items,
      totalMediaCount: result.data.totalMediaCount,
      referencedMediaCount: result.data.referencedMediaCount,
    });
  }

  function handleScanClick() {
    if (!environment) {
      return;
    }
    runScan(environment);
  }

  function toggleSelected(mediaId: string) {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(mediaId)) {
        next.delete(mediaId);
      } else {
        next.add(mediaId);
      }
      return next;
    });
  }

  function toggleSelectAll() {
    if (!scanResult) {
      return;
    }
    setSelectedIds((prev) =>
      prev.size === scanResult.items.length ? new Set() : new Set(scanResult.items.map((item) => item.mediaId))
    );
  }

  function handleJobSettled(job: GenerationJobDetail) {
    setDeleting(false);
    setProgress(null);
    // 先に再スキャンを走らせておく(runScan は内部で setMessage(null) を呼ぶため、
    // 結果メッセージの設定より後に呼ぶと React 18 のバッチ処理でメッセージが
    // 直後に消されてしまう)。
    if (environment) {
      runScan(environment);
    }
    const parsed = parseDeleteResult(job.resultPayload);
    if (job.status === "done" && parsed) {
      setResultSummary(parsed);
      setMessage({ type: "success", text: `${parsed.deletedCount}件削除しました${parsed.failedCount > 0 ? `(${parsed.failedCount}件失敗)` : ""}。` });
    } else {
      setMessage({ type: "error", text: "削除処理に失敗しました。" });
    }
  }

  const { startPolling } = useGenerationJobPolling(
    (job) => handleJobSettled(job),
    (job) => setProgress(parseJobProgress(job.resultPayload))
  );

  async function handleDelete() {
    if (!environment || selectedIds.size === 0) {
      return;
    }
    if (
      !window.confirm(
        `${selectedIds.size}件の未参照メディアを削除します。この操作は元に戻せません。よろしいですか?`
      )
    ) {
      return;
    }
    setDeleting(true);
    setMessage(null);
    setResultSummary(null);
    const result = await deleteMediaGarbageAction(projectId, environment, Array.from(selectedIds));
    if (result.error || result.jobId === undefined) {
      setDeleting(false);
      setMessage({ type: "error", text: result.error ?? "削除の開始に失敗しました。" });
      return;
    }
    startPolling(result.jobId);
  }

  if (availableEnvironments.length === 0) {
    return (
      <div className="rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-4 text-sm text-neutral-500 dark:text-neutral-400">
        <h3 className="mb-2 font-medium text-neutral-700 dark:text-neutral-300">ガベージコレクション</h3>
        自動構築(managed)またはSSH管理のWordPress環境が紐付いていないため、メディアのスキャンは行えません。
      </div>
    );
  }

  return (
    <div className="rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-4">
      <h3 className="mb-3 font-medium text-neutral-700 dark:text-neutral-300">ガベージコレクション</h3>
      <p className="mb-3 text-sm text-neutral-500 dark:text-neutral-400">
        投稿本文・アイキャッチ・主要なサイト設定(サイトアイコン・カスタムロゴ・ヘッダー/背景画像)のいずれからも
        参照されていないメディアを検出し、削除できます。
      </p>

      <div className="mb-3 flex flex-wrap items-end gap-2 text-sm">
        <label className="flex flex-col gap-1">
          <span className="text-neutral-600 dark:text-neutral-400">環境</span>
          <select
            value={environment}
            onChange={(e) => setEnvironment(e.target.value as ProjectEnvironment | "")}
            className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm"
          >
            <option value="">選択してください</option>
            {availableEnvironments.map((env) => (
              <option key={env.value} value={env.value}>
                {env.label}
              </option>
            ))}
          </select>
        </label>
        <button
          type="button"
          onClick={handleScanClick}
          disabled={!environment || scanning}
          className="rounded bg-neutral-900 px-3 py-2 text-sm text-white disabled:bg-neutral-200 disabled:text-neutral-600"
        >
          {scanning ? "スキャン中…" : "スキャン"}
        </button>
      </div>

      {message && (
        <p className={`mb-3 text-sm ${message.type === "error" ? "text-red-600" : "text-green-600"}`}>{message.text}</p>
      )}

      {resultSummary && resultSummary.failedCount > 0 && (
        <div className="mb-3 rounded border border-amber-200 bg-amber-50 p-2 text-sm text-amber-800 dark:border-amber-900 dark:bg-amber-950 dark:text-amber-300">
          削除に失敗したメディア:
          <ul className="mt-1 list-disc pl-5">
            {Object.entries(resultSummary.failures).map(([mediaId, reason]) => (
              <li key={mediaId}>
                ID {mediaId}: {reason}
              </li>
            ))}
          </ul>
        </div>
      )}

      {deleting && (
        <p className="mb-3 text-sm text-neutral-500 dark:text-neutral-400">
          {progress ? formatJobProgress(progress) : "削除を開始しています…"}
        </p>
      )}

      {scanResult && (
        <>
          <p className="mb-2 text-sm text-neutral-500 dark:text-neutral-400">
            全{scanResult.totalMediaCount}件中、参照あり{scanResult.referencedMediaCount}件・
            未参照{scanResult.items.length}件
          </p>

          {scanResult.items.length === 0 ? (
            <p className="text-sm text-neutral-500 dark:text-neutral-400">未参照のメディアはありません。</p>
          ) : (
            <>
              <div className="mb-2 flex items-center justify-between">
                <button
                  type="button"
                  onClick={toggleSelectAll}
                  className="text-sm text-blue-600 hover:underline dark:text-blue-400"
                >
                  {selectedIds.size === scanResult.items.length ? "全選択解除" : "全選択"}
                </button>
                <button
                  type="button"
                  onClick={handleDelete}
                  disabled={selectedIds.size === 0 || deleting}
                  className="rounded bg-red-600 px-3 py-1.5 text-sm text-white disabled:bg-neutral-200 disabled:text-neutral-600"
                >
                  {deleting ? "削除中…" : `選択した${selectedIds.size}件を削除`}
                </button>
              </div>

              <div className="overflow-x-auto rounded border border-neutral-200 dark:border-neutral-800">
                <table className="w-full text-left text-sm">
                  <thead className="border-b border-neutral-200 dark:border-neutral-800 bg-neutral-50 dark:bg-neutral-800 text-neutral-500 dark:text-neutral-400">
                    <tr>
                      <th className="px-2 py-1.5">
                        <input
                          type="checkbox"
                          checked={selectedIds.size === scanResult.items.length}
                          onChange={toggleSelectAll}
                        />
                      </th>
                      <th className="px-2 py-1.5">タイトル</th>
                      <th className="px-2 py-1.5">種類</th>
                      <th className="px-2 py-1.5">アップロード日</th>
                      <th className="px-2 py-1.5">URL</th>
                    </tr>
                  </thead>
                  <tbody>
                    {scanResult.items.map((item) => (
                      <tr key={item.mediaId} className="border-t border-neutral-100 dark:border-neutral-800">
                        <td className="px-2 py-1.5">
                          <input
                            type="checkbox"
                            checked={selectedIds.has(item.mediaId)}
                            onChange={() => toggleSelected(item.mediaId)}
                            disabled={deleting}
                          />
                        </td>
                        <td className="px-2 py-1.5 text-neutral-700 dark:text-neutral-300">{item.title || "(無題)"}</td>
                        <td className="px-2 py-1.5 text-neutral-500 dark:text-neutral-400">{item.mimeType}</td>
                        <td className="px-2 py-1.5 text-neutral-500 dark:text-neutral-400">{item.uploadedAt}</td>
                        <td className="px-2 py-1.5">
                          <a
                            href={item.guid}
                            target="_blank"
                            rel="noreferrer"
                            className="text-blue-600 hover:underline dark:text-blue-400"
                          >
                            {item.guid}
                          </a>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </>
          )}
        </>
      )}
    </div>
  );
}
