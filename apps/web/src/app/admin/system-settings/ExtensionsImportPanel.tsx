"use client";

import { useActionState, useState } from "react";
import type { ExtensionsImportRoute } from "@/lib/apiClient";
import {
  applyExtensionsConnectionsAction,
  fetchExtensionsConnectionsAction,
  type ExtensionsApplyState,
  type ExtensionsFetchState,
} from "./extensionsImportActions";

const initialFetchState: ExtensionsFetchState = {};
const initialApplyState: ExtensionsApplyState = {};

/**
 * extensions のポータルから Ollama / ComfyUI / Penpot の接続先を取り込む欄(issue #1783)。
 * 「取得」は候補を表示するだけで、「反映」を押したときだけシステム設定へ保存する。
 */
export function ExtensionsImportPanel({ portalUrl }: { portalUrl: string }) {
  const [fetchState, fetchAction, fetching] = useActionState(fetchExtensionsConnectionsAction, initialFetchState);
  const [applyState, applyAction, applying] = useActionState(applyExtensionsConnectionsAction, initialApplyState);
  const [url, setUrl] = useState(portalUrl);
  const [route, setRoute] = useState<ExtensionsImportRoute>("SAME_PC");
  const candidates = fetchState.candidates;
  const ignoredIds = fetchState.ignoredIds;

  return (
    <section className="space-y-4 rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-5">
      <div>
        <h2 className="font-medium">extensions から取り込む</h2>
        <p className="text-sm text-neutral-600 dark:text-neutral-400">
          lets_blog_server_extensions のポータルのURLを指定して「取得」を押すと、Ollama / ComfyUI / Penpot の
          接続先の候補を表示します。「反映」を押したときだけシステム設定へ保存します。
        </p>
      </div>
      <form action={fetchAction} className="space-y-3">
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-neutral-600 dark:text-neutral-400">ポータルのURL</span>
          <input
            name="portalUrl"
            type="text"
            value={url}
            onChange={(e) => setUrl(e.target.value)}
            placeholder="http://lbx-portal:3100"
            autoComplete="off"
            className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm"
          />
        </label>
        <div className="flex flex-wrap gap-4 text-sm">
          <label className="flex items-center gap-2">
            <input
              type="radio"
              name="route"
              value="SAME_PC"
              checked={route === "SAME_PC"}
              onChange={() => setRoute("SAME_PC")}
            />
            同じ PC(共有ネットワーク)
          </label>
          <label className="flex items-center gap-2">
            <input
              type="radio"
              name="route"
              value="OTHER_HOST"
              checked={route === "OTHER_HOST"}
              onChange={() => setRoute("OTHER_HOST")}
            />
            別ホスト(公開 URL)
          </label>
        </div>
        <button
          type="submit"
          disabled={fetching}
          className="rounded bg-neutral-900 px-4 py-2 text-sm text-white disabled:bg-neutral-200 disabled:text-neutral-600"
        >
          取得
        </button>
      </form>

      {fetchState.error && (
        <p role="alert" className="text-sm text-red-600">
          {fetchState.error}
        </p>
      )}

      {candidates && candidates.length === 0 && <p className="text-sm">取り込める接続先がありません。</p>}
      {candidates && candidates.length > 0 && (
        <form action={applyAction} className="space-y-3">
          <input type="hidden" name="portalUrl" defaultValue={fetchState.portalUrl} />
          <table aria-label="取り込み候補" className="w-full text-left text-sm">
            <thead>
              <tr>
                <th scope="col">設定項目</th>
                <th scope="col">現在の値</th>
                <th scope="col">取り込む値</th>
              </tr>
            </thead>
            <tbody>
              {candidates.map((candidate) => (
                <tr key={candidate.key}>
                  <td>{candidate.key}</td>
                  <td>{candidate.currentValue ?? "(未設定)"}</td>
                  <td>{candidate.candidateValue}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {candidates.map((candidate) => (
            <input
              key={candidate.key}
              type="hidden"
              name={`value:${candidate.key}`}
              value={candidate.candidateValue}
            />
          ))}
          <button
            type="submit"
            disabled={applying}
            className="rounded bg-neutral-900 px-4 py-2 text-sm text-white disabled:bg-neutral-200 disabled:text-neutral-600"
          >
            反映
          </button>
        </form>
      )}
      {ignoredIds && ignoredIds.length > 0 && (
        <p className="text-sm text-neutral-600 dark:text-neutral-400">取り込み対象外: {ignoredIds.join(", ")}</p>
      )}

      {applyState.error && (
        <p role="alert" className="text-sm text-red-600">
          {applyState.error}
        </p>
      )}
      {applyState.saved && (
        <p role="status" className="text-sm text-green-600">
          反映しました(保存した項目: {applyState.saved.join(", ")})
        </p>
      )}
      {applyState.rejected && applyState.rejected.length > 0 && (
        <ul aria-label="保存されなかった項目" className="list-disc pl-5 text-sm text-red-600">
          {applyState.rejected.map((r) => (
            <li key={r.key}>
              {r.key}: {r.reason}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
