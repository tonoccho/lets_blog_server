"use client";

import { useActionState, useState } from "react";
import type { PluginKind, PluginRegistration } from "@/lib/apiClient";
import {
  activatePluginRegistrationAction,
  deletePluginRegistrationAction,
  savePluginRegistrationAction,
  type PluginRegistrationActionState,
} from "./pluginRegistrationActions";

const initialState: PluginRegistrationActionState = {};

const KINDS: { kind: PluginKind; label: string; multiple: boolean }[] = [
  { kind: "LLM", label: "LLM", multiple: true },
  { kind: "IMAGE_GENERATION", label: "画像生成", multiple: true },
  { kind: "DIAGRAM_RENDERER", label: "図のレンダリング", multiple: false },
  { kind: "PENPOT", label: "Penpot", multiple: false },
];

const INPUT_CLASS = "rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm";
const BUTTON_CLASS =
  "rounded bg-neutral-900 px-3 py-1 text-sm text-white disabled:bg-neutral-200 disabled:text-neutral-600";
const SECONDARY_BUTTON_CLASS =
  "rounded border border-neutral-300 dark:border-neutral-700 px-3 py-1 text-sm disabled:text-neutral-400";

/**
 * システム全体のプラグインの登録(issue #1807)。種類ごとに、登録の一覧・追加・編集・削除と、
 * LLM・画像生成の使用中の切り替えを行う。保存時の接続先の検査で拒否された理由は、その種類の欄に出す。
 */
export function PluginRegistrationsPanel({ registrations }: { registrations: PluginRegistration[] }) {
  return (
    <section className="space-y-4 rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-5">
      <div>
        <h2 className="font-medium">プラグインの登録</h2>
        <p className="text-sm text-neutral-600 dark:text-neutral-400">
          各種類の利用側が登録へ切り替わるまでは、従来の設定項目が使われます。
        </p>
      </div>
      {KINDS.map(({ kind, label, multiple }) => (
        <KindSection
          key={kind}
          kind={kind}
          label={label}
          multiple={multiple}
          registrations={registrations.filter((r) => r.kind === kind)}
        />
      ))}
    </section>
  );
}

function KindSection({
  kind,
  label,
  multiple,
  registrations,
}: {
  kind: PluginKind;
  label: string;
  multiple: boolean;
  registrations: PluginRegistration[];
}) {
  const [editingId, setEditingId] = useState<number | null>(null);
  const [saveState, saveAction, saving] = useActionState(savePluginRegistrationAction, initialState);
  const [deleteState, deleteAction, deleting] = useActionState(deletePluginRegistrationAction, initialState);
  const [activateState, activateAction, activating] = useActionState(
    activatePluginRegistrationAction,
    initialState,
  );
  const error = saveState.error ?? deleteState.error ?? activateState.error;
  const busy = saving || deleting || activating;

  return (
    <section aria-label={`${label} の登録`} className="space-y-2 border-t border-neutral-200 dark:border-neutral-800 pt-3">
      <h3 className="text-sm font-medium">{label}</h3>
      {registrations.length === 0 ? (
        <p className="text-sm text-neutral-600 dark:text-neutral-400">登録はありません</p>
      ) : (
        <ul className="space-y-2">
          {registrations.map((registration) => (
            <li key={registration.id} className="space-y-2 text-sm">
              {editingId === registration.id ? (
                <RegistrationForm
                  label={`${registration.name} を編集`}
                  kind={kind}
                  registration={registration}
                  submitLabel="更新"
                  action={saveAction}
                  pending={busy}
                  onCancel={() => setEditingId(null)}
                  onSubmitted={() => setEditingId(null)}
                />
              ) : (
                <div className="flex flex-wrap items-center gap-3">
                  <span className="font-medium">{registration.name}</span>
                  <span className="text-neutral-600 dark:text-neutral-400">{registration.url}</span>
                  {registration.drawioUrl && (
                    <span className="text-neutral-600 dark:text-neutral-400">{registration.drawioUrl}</span>
                  )}
                  {registration.inUse && (
                    <span className="rounded bg-green-100 px-2 py-0.5 text-green-800 dark:bg-green-900 dark:text-green-100">
                      使用中
                    </span>
                  )}
                  <button type="button" className={SECONDARY_BUTTON_CLASS} onClick={() => setEditingId(registration.id)}>
                    編集
                  </button>
                  {multiple && !registration.inUse && (
                    <form action={activateAction}>
                      <input type="hidden" name="id" value={registration.id} />
                      <button type="submit" disabled={busy} className={SECONDARY_BUTTON_CLASS}>
                        使用中にする
                      </button>
                    </form>
                  )}
                  <form action={deleteAction}>
                    <input type="hidden" name="id" value={registration.id} />
                    <button type="submit" disabled={busy} className={SECONDARY_BUTTON_CLASS}>
                      削除
                    </button>
                  </form>
                </div>
              )}
            </li>
          ))}
        </ul>
      )}
      <RegistrationForm
        label={`${label} を追加`}
        kind={kind}
        submitLabel="追加"
        action={saveAction}
        pending={busy}
      />
      {error && (
        <p role="alert" className="text-sm text-red-600">
          {error}
        </p>
      )}
      {!error && saveState.saved && (
        <p role="status" className="text-sm text-green-600">
          保存しました
        </p>
      )}
    </section>
  );
}

function RegistrationForm({
  label,
  kind,
  registration,
  submitLabel,
  action,
  pending,
  onCancel,
  onSubmitted,
}: {
  label: string;
  kind: PluginKind;
  registration?: PluginRegistration;
  submitLabel: string;
  action: (formData: FormData) => void;
  pending: boolean;
  onCancel?: () => void;
  onSubmitted?: () => void;
}) {
  return (
    <form
      aria-label={label}
      action={(formData) => {
        action(formData);
        onSubmitted?.();
      }}
      className="flex flex-wrap items-end gap-3"
    >
      {registration ? <input type="hidden" name="id" value={registration.id} /> : null}
      <input type="hidden" name="kind" value={kind} />
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
          placeholder="http://lbx-ollama:11434/v1"
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
