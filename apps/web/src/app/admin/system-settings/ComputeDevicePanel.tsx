"use client";

import { useActionState, useEffect, useId, useState } from "react";
import { useRouter } from "next/navigation";
import type { ComputeDevice, ComputeDeviceStatus } from "@/lib/apiClient";
import { applyComputeDeviceAction, type ApplyComputeDeviceFormState } from "./actions";

const initialState: ApplyComputeDeviceFormState = {};

/** 適用中の進行状態を取得し直す間隔(ms)。 */
const POLL_INTERVAL_MS = 2000;

const DEVICES: ComputeDevice[] = ["GPU", "CPU"];

/** 切り替え対象ごとの見出しの名前と説明(issue #1585)。未知の対象は対象名をそのまま使う。 */
const TARGET_LABELS: Record<string, { name: string; description: string }> = {
  comfyui: { name: "ComfyUI", description: "画像生成(ComfyUI)" },
  ollama: { name: "Ollama", description: "ローカル LLM(Ollama)" },
};

function targetLabel(target: string): { name: string; description: string } {
  return TARGET_LABELS[target] ?? { name: target, description: target };
}

function currentLabel(status: ComputeDeviceStatus): string {
  switch (status.currentDevice) {
    case "GPU":
      return "GPU";
    case "CPU":
      return status.cpuFixed ? "CPU(固定)" : "CPU";
    case "BOTH":
      return "GPU と CPU の両方が稼働中";
    default:
      return "停止中";
  }
}

function isSelectable(status: ComputeDeviceStatus, device: ComputeDevice): boolean {
  return device === "GPU" ? status.gpuSelectable : status.cpuSelectable;
}

function unavailableReason(status: ComputeDeviceStatus, device: ComputeDevice): string | null {
  return device === "GPU" ? status.gpuUnavailableReason : status.cpuUnavailableReason;
}

function ApplyStateView({ status }: { status: ComputeDeviceStatus }) {
  const { state, requestedDevice, message } = status.apply;
  if (state === "IDLE") {
    return null;
  }
  const text =
    state === "APPLYING"
      ? `適用中です(${requestedDevice ?? ""}構成へ切り替えています)。`
      : state === "SUCCEEDED"
        ? `適用が完了しました。${message ?? ""}`
        : `適用に失敗しました: ${message ?? ""}`;
  return (
    <p
      data-testid="compute-device-apply-state"
      role="status"
      className={state === "FAILED" ? "text-sm text-red-600 dark:text-red-400" : "text-sm"}
    >
      {text}
    </p>
  );
}

/**
 * ComfyUI・Ollama の演算デバイス(GPU / CPU)の欄(issue #1399 / #1585)。対象ごとに1欄で、互いに独立している。`AppSettingsPanel` の「保存する設定」とは別の
 * 「適用する操作」である。現在の構成は実際に動いているコンテナから判定した値を表示する。
 * 適用中は一定間隔でサーバーコンポーネントを再取得して進行状態を更新する。
 */
export function ComputeDevicePanel({ status }: { status: ComputeDeviceStatus }) {
  const [state, formAction, pending] = useActionState(applyComputeDeviceAction, initialState);
  const { refresh } = useRouter();
  const applying = status.apply.state === "APPLYING";
  const headingId = useId();
  const label = targetLabel(status.target);
  const [selected, setSelected] = useState<ComputeDevice | null>(
    () => DEVICES.find((d) => isSelectable(status, d) && d !== status.currentDevice) ?? null
  );

  useEffect(() => {
    if (!applying) {
      return;
    }
    const timer = setInterval(refresh, POLL_INTERVAL_MS);
    return () => clearInterval(timer);
  }, [applying, refresh]);

  return (
    <section
      aria-labelledby={headingId}
      className="space-y-3 rounded border border-neutral-200 p-4 dark:border-neutral-800"
    >
      <h2 id={headingId} className="text-lg font-semibold">
        演算デバイス({label.name})
      </h2>
      <p className="text-sm text-neutral-600 dark:text-neutral-400">
        {label.description}を GPU と CPU のどちらで動かすかを切り替えます。保存する設定ではなく、
        コンテナの起動・停止を伴う操作です。適用中は{label.name}を使う機能が失敗することがあり、成功しなかった場合は
        元の構成へ戻ります。
      </p>
      <p data-testid="compute-device-current" className="text-sm font-medium">
        現在の構成: {currentLabel(status)}
      </p>
      <form action={formAction} className="space-y-3">
        <input type="hidden" name="target" value={status.target} />
        <div role="radiogroup" aria-label="演算デバイス" className="space-y-2">
          {DEVICES.map((device) => {
            const reason = unavailableReason(status, device);
            return (
              <div key={device}>
                <label className="flex items-center gap-2 text-sm">
                  <input
                    type="radio"
                    name="device"
                    value={device}
                    checked={selected === device}
                    disabled={!isSelectable(status, device) || applying}
                    onChange={() => setSelected(device)}
                  />
                  {device}
                </label>
                {reason && <p className="ml-6 text-xs text-neutral-600 dark:text-neutral-400">{reason}</p>}
              </div>
            );
          })}
        </div>
        <button
          type="submit"
          disabled={pending || applying || selected === null}
          className="rounded bg-neutral-900 px-3 py-1.5 text-sm text-white disabled:opacity-50 dark:bg-neutral-100 dark:text-neutral-900"
        >
          適用する
        </button>
        {state.error && (
          <p role="alert" className="text-sm text-red-600 dark:text-red-400">
            {state.error}
          </p>
        )}
        {state.success && <p className="text-sm">適用を受け付けました。</p>}
      </form>
      <ApplyStateView status={status} />
    </section>
  );
}
