"use client";

import { useActionState, useState, useTransition } from "react";
import {
  disableTwoFactorAction,
  startTwoFactorSetupAction,
  verifyTwoFactorSetupAction,
  type TwoFactorSetupState,
  type VerifyTwoFactorState,
} from "./actions";

const setupInitialState: TwoFactorSetupState = {};
const verifyInitialState: VerifyTwoFactorState = {};

export function TwoFactorSettings({ initialEnabled }: { initialEnabled: boolean }) {
  // サーバーアクションの結果に応じて有効/無効表示を切り替えるため、
  // propからの初期値をuseEffectで同期するのではなく、アクション結果から直接導出する。
  const [manualEnabled, setManualEnabled] = useState<boolean | null>(null);
  const [setupState, startSetupFormAction, setupPending] = useActionState(
    startTwoFactorSetupAction,
    setupInitialState
  );
  const [verifyState, verifyFormAction, verifyPending] = useActionState(
    verifyTwoFactorSetupAction,
    verifyInitialState
  );
  const [isDisabling, startDisableTransition] = useTransition();

  const enabled = manualEnabled ?? (verifyState.success ? true : initialEnabled);

  function handleDisable() {
    if (!window.confirm("2FAを無効化しますか?再度有効化するにはQRコードの再スキャンが必要になります。")) {
      return;
    }
    startDisableTransition(async () => {
      await disableTwoFactorAction();
      setManualEnabled(false);
    });
  }

  if (enabled) {
    return (
      <div className="space-y-3 rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-5">
        <p className="text-sm text-green-600">2段階認証(TOTP)は有効です。</p>
        <button
          type="button"
          onClick={handleDisable}
          disabled={isDisabling}
          className="text-sm text-red-600 hover:underline disabled:text-neutral-600 disabled:no-underline"
        >
          {isDisabling ? "無効化中…" : "2FAを無効化"}
        </button>
      </div>
    );
  }

  if (!setupState.qrCodeDataUrl) {
    return (
      <form action={startSetupFormAction} className="space-y-3 rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-5">
        <p className="text-sm text-neutral-600 dark:text-neutral-400">
          Google Authenticator等の認証アプリを使った2段階認証(TOTP)を設定できます。
        </p>
        {setupState.error && <p className="text-sm text-red-600">{setupState.error}</p>}
        <button
          type="submit"
          disabled={setupPending}
          className="rounded bg-neutral-900 px-4 py-2 text-sm text-white disabled:bg-neutral-200 disabled:text-neutral-600"
        >
          {setupPending ? "準備中…" : "2FAを有効化"}
        </button>
      </form>
    );
  }

  const backupCodesText = (setupState.backupCodes ?? []).join("\n");

  return (
    <div className="space-y-4 rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-5">
      <div className="space-y-2">
        <p className="text-sm font-medium">1. QRコードを認証アプリで読み取ってください</p>
        {/* データURLのQRコードのためnext/imageではなくimgタグを使用 */}
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img src={setupState.qrCodeDataUrl} alt="TOTP設定用QRコード" width={160} height={160} />
      </div>

      <div className="space-y-2">
        <p className="text-sm font-medium">2. バックアップコードを保管してください</p>
        <p className="text-sm text-neutral-600 dark:text-neutral-400">
          認証アプリが使えなくなった場合の代替手段です。このコードは今だけ表示されます。安全な場所に保管してください。
        </p>
        <ul className="grid grid-cols-2 gap-1 rounded border border-neutral-200 dark:border-neutral-800 bg-neutral-50 dark:bg-neutral-800 p-3 font-mono text-sm">
          {setupState.backupCodes?.map((code) => (
            <li key={code}>{code}</li>
          ))}
        </ul>
        <a
          href={`data:text/plain;charset=utf-8,${encodeURIComponent(backupCodesText)}`}
          download="letsblog-backup-codes.txt"
          className="text-sm text-blue-600 hover:underline"
        >
          バックアップコードをダウンロード
        </a>
      </div>

      <form action={verifyFormAction} className="space-y-3">
        <p className="text-sm font-medium">3. 認証アプリに表示された6桁のコードを入力して確定</p>
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-neutral-600 dark:text-neutral-400">認証コード</span>
          <input
            name="code"
            inputMode="numeric"
            pattern="[0-9]{6}"
            maxLength={6}
            required
            className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm"
          />
        </label>
        {verifyState.error && <p className="text-sm text-red-600">{verifyState.error}</p>}
        <button
          type="submit"
          disabled={verifyPending}
          className="rounded bg-neutral-900 px-4 py-2 text-sm text-white disabled:bg-neutral-200 disabled:text-neutral-600"
        >
          {verifyPending ? "確認中…" : "コードを確認して有効化"}
        </button>
      </form>
    </div>
  );
}
