"use client";

import { useEffect, useState } from "react";
import type { PluginKind, ProjectPluginRegistration } from "@/lib/apiClient";
import {
  activateProjectPluginRegistrationAction,
  clearProjectPluginRegistrationCredentialAction,
  deleteProjectPluginRegistrationAction,
  fetchProjectPluginRegistrationsAction,
  saveProjectPluginRegistrationAction,
} from "./projectPluginRegistrationActions";
import type { ProjectPluginRegistrationActionResult } from "./projectPluginRegistrationActions";

/** Penpot は上書きの対象にしない(接続先はシステム全体に1つ)ので、欄を出さない。 */
const KINDS: { kind: PluginKind; label: string; multiple: boolean }[] = [
  { kind: "LLM", label: "LLM", multiple: true },
  { kind: "IMAGE_GENERATION", label: "画像生成", multiple: true },
  { kind: "DIAGRAM_RENDERER", label: "図のレンダリング", multiple: false },
];

const INPUT_CLASS = "rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm";
const BUTTON_CLASS =
  "rounded bg-neutral-900 px-3 py-1 text-sm text-white disabled:bg-neutral-200 disabled:text-neutral-600";
const SECONDARY_BUTTON_CLASS =
  "rounded border border-neutral-300 dark:border-neutral-700 px-3 py-1 text-sm disabled:text-neutral-400";

function text(formData: FormData, name: string): string {
  return String(formData.get(name) ?? "");
}

/**
 * プロジェクト単位のプラグインの登録の上書き(issue #1808)。種類ごとに、上書きの一覧・追加・編集・削除、
 * LLM・画像生成の使用中の切り替え、認証情報の消去を行う。認証情報は書き込みだけで、値は表示せず「設定済み」だけを示す。
 * 保存時の接続先の検査で拒否された理由は、その種類の欄に出す。
 */
export function ProjectPluginOverridesPanel({ projectId }: { projectId: number }) {
  const [registrations, setRegistrations] = useState<ProjectPluginRegistration[] | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);

  async function reload() {
    const result = await fetchProjectPluginRegistrationsAction(projectId);
    applyFetchResult(result);
  }

  function applyFetchResult(result: { data?: ProjectPluginRegistration[]; error?: string }) {
    if (result.data) {
      setRegistrations(result.data);
      setLoadError(null);
    } else {
      setLoadError(result.error ?? "取得に失敗しました");
    }
  }

  useEffect(() => {
    let active = true;
    void fetchProjectPluginRegistrationsAction(projectId).then((result) => {
      if (active) applyFetchResult(result);
    });
    return () => {
      active = false;
    };
    // applyFetchResult は state 更新関数だけを使うので、projectId ごとに1回だけ取得する。
  }, [projectId]);

  return (
    <div className="space-y-4">
      <p className="text-sm text-neutral-600 dark:text-neutral-400">
        このプロジェクトだけの接続先を登録できます。上書きした種類は、システム全体の登録を見ません。
      </p>
      {loadError && (
        <p role="alert" className="text-sm text-red-600">
          取得に失敗しました: {loadError}
        </p>
      )}
      {registrations === null
        ? !loadError && <p className="text-sm text-neutral-500 dark:text-neutral-400">読み込み中…</p>
        : KINDS.map(({ kind, label, multiple }) => (
            <KindSection
              key={kind}
              projectId={projectId}
              kind={kind}
              label={label}
              multiple={multiple}
              registrations={registrations.filter((r) => r.kind === kind)}
              onChanged={reload}
            />
          ))}
    </div>
  );
}

function KindSection({
  projectId,
  kind,
  label,
  multiple,
  registrations,
  onChanged,
}: {
  projectId: number;
  kind: PluginKind;
  label: string;
  multiple: boolean;
  registrations: ProjectPluginRegistration[];
  onChanged: () => Promise<void>;
}) {
  const [editingId, setEditingId] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  /** 操作を実行し、成功なら再取得、失敗なら理由をこの欄に出す。 */
  async function perform(operation: () => Promise<ProjectPluginRegistrationActionResult>) {
    setBusy(true);
    try {
      const result = await operation();
      if (result.error) {
        setError(result.error);
        return;
      }
      setError(null);
      await onChanged();
    } finally {
      setBusy(false);
    }
  }

  function save(formData: FormData) {
    const idText = text(formData, "id");
    return perform(() =>
      saveProjectPluginRegistrationAction(projectId, {
        ...(idText ? { id: Number(idText) } : {}),
        kind,
        name: text(formData, "name"),
        url: text(formData, "url"),
        drawioUrl: text(formData, "drawioUrl").trim() || null,
        credential: text(formData, "credential").trim() || null,
      }),
    );
  }

  return (
    <section aria-label={`${label} の上書き`} className="space-y-2 border-t border-neutral-200 dark:border-neutral-800 pt-3">
      <h4 className="text-sm font-medium">{label}</h4>
      {registrations.length === 0 ? (
        <p className="text-sm text-neutral-600 dark:text-neutral-400">上書きはありません</p>
      ) : (
        <ul className="space-y-2">
          {registrations.map((registration) => (
            <li key={registration.id} className="space-y-2 text-sm">
              {editingId === registration.id ? (
                <OverrideForm
                  label={`${registration.name} を編集`}
                  kind={kind}
                  registration={registration}
                  submitLabel="更新"
                  action={async (formData) => {
                    setEditingId(null);
                    await save(formData);
                  }}
                  pending={busy}
                  onCancel={() => setEditingId(null)}
                />
              ) : (
                <div className="flex flex-wrap items-center gap-3">
                  <span className="font-medium">{registration.name}</span>
                  <span className="text-neutral-600 dark:text-neutral-400">{registration.url}</span>
                  {registration.drawioUrl && (
                    <span className="text-neutral-600 dark:text-neutral-400">{registration.drawioUrl}</span>
                  )}
                  <span className="text-neutral-600 dark:text-neutral-400">
                    認証情報: {registration.credentialConfigured ? "設定済み" : "未設定"}
                  </span>
                  {registration.inUse && (
                    <span className="rounded bg-green-100 px-2 py-0.5 text-green-800 dark:bg-green-900 dark:text-green-100">
                      使用中
                    </span>
                  )}
                  <button type="button" className={SECONDARY_BUTTON_CLASS} onClick={() => setEditingId(registration.id)}>
                    編集
                  </button>
                  {multiple && !registration.inUse && (
                    <button
                      type="button"
                      disabled={busy}
                      className={SECONDARY_BUTTON_CLASS}
                      onClick={() => perform(() => activateProjectPluginRegistrationAction(projectId, registration.id))}
                    >
                      使用中にする
                    </button>
                  )}
                  {registration.credentialConfigured && (
                    <button
                      type="button"
                      disabled={busy}
                      className={SECONDARY_BUTTON_CLASS}
                      onClick={() =>
                        perform(() => clearProjectPluginRegistrationCredentialAction(projectId, registration.id))
                      }
                    >
                      認証情報を消去
                    </button>
                  )}
                  <button
                    type="button"
                    disabled={busy}
                    className={SECONDARY_BUTTON_CLASS}
                    onClick={() => perform(() => deleteProjectPluginRegistrationAction(projectId, registration.id))}
                  >
                    削除
                  </button>
                </div>
              )}
            </li>
          ))}
        </ul>
      )}
      <OverrideForm label={`${label} を追加`} kind={kind} submitLabel="追加" action={save} pending={busy} />
      {error && (
        <p role="alert" className="text-sm text-red-600">
          {error}
        </p>
      )}
    </section>
  );
}

function OverrideForm({
  label,
  kind,
  registration,
  submitLabel,
  action,
  pending,
  onCancel,
}: {
  label: string;
  kind: PluginKind;
  registration?: ProjectPluginRegistration;
  submitLabel: string;
  action: (formData: FormData) => void | Promise<void>;
  pending: boolean;
  onCancel?: () => void;
}) {
  return (
    <form aria-label={label} action={action} className="flex flex-wrap items-end gap-3">
      {registration ? <input type="hidden" name="id" value={registration.id} /> : null}
      <label className="flex flex-col gap-1 text-sm">
        <span className="text-neutral-600 dark:text-neutral-400">名前</span>
        <input name="name" type="text" defaultValue={registration?.name ?? ""} autoComplete="off" className={INPUT_CLASS} />
      </label>
      <label className="flex flex-col gap-1 text-sm">
        <span className="text-neutral-600 dark:text-neutral-400">URL</span>
        <input
          name="url"
          type="text"
          defaultValue={registration?.url ?? ""}
          placeholder="http://gpu-pc.example:11434/v1"
          autoComplete="off"
          className={INPUT_CLASS}
        />
      </label>
      {kind === "DIAGRAM_RENDERER" && (
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-neutral-600 dark:text-neutral-400">drawioUrl(任意)</span>
          <input
            name="drawioUrl"
            type="text"
            defaultValue={registration?.drawioUrl ?? ""}
            autoComplete="off"
            className={INPUT_CLASS}
          />
        </label>
      )}
      <label className="flex flex-col gap-1 text-sm">
        <span className="text-neutral-600 dark:text-neutral-400">認証情報(任意)</span>
        <input
          name="credential"
          type="password"
          autoComplete="new-password"
          placeholder={registration?.credentialConfigured ? "空なら保存済みの値のまま" : ""}
          className={INPUT_CLASS}
        />
      </label>
      <button type="submit" disabled={pending} className={BUTTON_CLASS}>
        {submitLabel}
      </button>
      {onCancel && (
        <button type="button" onClick={onCancel} className={SECONDARY_BUTTON_CLASS}>
          キャンセル
        </button>
      )}
    </form>
  );
}
