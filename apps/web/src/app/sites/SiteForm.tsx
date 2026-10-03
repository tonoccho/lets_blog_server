"use client";

import { useActionState, useState, useTransition } from "react";
import { useRef, useEffect } from "react";
import type { SavedSshKeyPair } from "@/lib/apiClient";
import { generateSshKeyPairAction, registerSiteAction, RegisterSiteState } from "./actions";

const initialState: RegisterSiteState = {};

type SshKeyMode = "existing" | "new";

export function SiteForm({
  sshKeyPairs,
  defaultAdminPath,
}: {
  sshKeyPairs: SavedSshKeyPair[];
  defaultAdminPath: string | null;
}) {
  const [state, formAction, pending] = useActionState(registerSiteAction, initialState);
  const [sshKeyMode, setSshKeyMode] = useState<SshKeyMode>(sshKeyPairs.length > 0 ? "existing" : "new");
  const [privateKeyPem, setPrivateKeyPem] = useState("");
  const [publicKeyLine, setPublicKeyLine] = useState("");
  const [keyGenError, setKeyGenError] = useState<string | null>(null);
  const [keyGenPending, startKeyGenTransition] = useTransition();
  const formRef = useRef<HTMLFormElement>(null);

  useEffect(() => {
    if (state.success) {
      formRef.current?.reset();
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setSshKeyMode(sshKeyPairs.length > 0 ? "existing" : "new");
      setPrivateKeyPem("");
      setPublicKeyLine("");
      setKeyGenError(null);
    }
  }, [state.success, sshKeyPairs.length]);

  function handleGenerateKeyPair() {
    setKeyGenError(null);
    startKeyGenTransition(async () => {
      const result = await generateSshKeyPairAction("letsblog");
      if (result.error) {
        setKeyGenError(result.error);
        return;
      }
      setPrivateKeyPem(result.privateKeyPem ?? "");
      setPublicKeyLine(result.publicKeyLine ?? "");
    });
  }

  return (
    <form ref={formRef} action={formAction} className="space-y-3 rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-5">
      <h2 className="font-medium">サイトを登録</h2>
      <p className="text-sm text-neutral-700 dark:text-neutral-300">
        サイト登録は既存のWordPressサイトの認証情報を保存するだけです。サーバー側で新規にサイトや
        リソースを作成する「プロビジョニング」は行いません。登録時に入力内容で疎通確認を行いますが、
        失敗した場合も登録自体は完了します(後から認証情報を見直してください)。
      </p>
      <input type="hidden" name="cmsType" value="WORDPRESS" />
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        <Field name="name" label="表示名" placeholder="My Blog" />
        <Field name="siteKey" label="サイトキー" placeholder="main" />
      </div>

      <label className="flex flex-col gap-1 text-sm">
        <span className="text-neutral-700 dark:text-neutral-300 font-medium">管理画面パス(任意)</span>
        <input
          name="adminPath"
          placeholder={defaultAdminPath ? `既定: ${defaultAdminPath}` : "空欄ならシステム設定の既定値"}
          className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-500"
        />
        <span className="text-xs text-neutral-500 dark:text-neutral-400">
          空欄ならシステム設定の既定値を使います。入力すると、このサイトだけ別のパスで上書きします。
        </span>
      </label>

      <div className="space-y-3 rounded border border-neutral-200 dark:border-neutral-800 p-3">
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <Field name="baseUrl" label="WordPressの公開URL" placeholder="https://example.com" />
          <Field name="sshHost" label="SSHホスト" placeholder="203.0.113.5" />
          <Field name="sshPort" label="SSHポート(既定22)" placeholder="22" required={false} />
          <Field name="sshUser" label="SSHユーザー" placeholder="deploy" />
          <Field
            name="wpPath"
            label="WordPressインストール先ディレクトリ(wp-cliの--path)"
            placeholder="/home/deploy/public_html (wp-cli本体のパスではありません)"
            wide
          />
        </div>

        <div className="space-y-2">
          {sshKeyPairs.length > 0 && (
            <div className="flex gap-4 text-sm">
              <label className="flex items-center gap-1.5">
                <input
                  type="radio"
                  checked={sshKeyMode === "existing"}
                  onChange={() => setSshKeyMode("existing")}
                />
                保存済みの鍵ペアを使う
              </label>
              <label className="flex items-center gap-1.5">
                <input type="radio" checked={sshKeyMode === "new"} onChange={() => setSshKeyMode("new")} />
                新しい鍵ペアを生成する
              </label>
            </div>
          )}

          {sshKeyMode === "existing" && sshKeyPairs.length > 0 ? (
            <label className="flex flex-col gap-1 text-sm">
              <span className="text-neutral-700 dark:text-neutral-300 font-medium">SSH鍵ペア</span>
              <select
                name="sshKeyPairId"
                required
                className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-500"
              >
                {sshKeyPairs.map((keyPair) => (
                  <option key={keyPair.id} value={keyPair.id}>
                    {keyPair.name}
                    {keyPair.comment ? `(${keyPair.comment})` : ""}
                  </option>
                ))}
              </select>
              <p className="text-xs text-neutral-500 dark:text-neutral-400">
                公開鍵をリモートサーバーの対象ユーザーの<code>~/.ssh/authorized_keys</code>
                へ追記済みであることを確認してください(公開鍵は<a href="/admin/ssh-keys" className="underline">
                  SSH鍵管理画面
                </a>
                で確認できます)。
              </p>
            </label>
          ) : (
            <>
              <button
                type="button"
                onClick={handleGenerateKeyPair}
                disabled={keyGenPending}
                className="rounded bg-neutral-100 dark:bg-neutral-800 px-3 py-1.5 text-sm text-neutral-900 dark:text-neutral-50 hover:bg-neutral-200 dark:hover:bg-neutral-700 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-500 disabled:opacity-50"
              >
                {keyGenPending ? "鍵ペアを生成中…" : "SSH鍵ペアを生成"}
              </button>
              {keyGenError && <p className="text-sm text-red-600">{keyGenError}</p>}
              {publicKeyLine && (
                <div className="space-y-1">
                  <p className="text-sm text-neutral-700 dark:text-neutral-300">
                    以下の公開鍵をリモートサーバーの対象ユーザーの<code>~/.ssh/authorized_keys</code>
                    へ手動で追記してから登録してください。
                  </p>
                  <textarea
                    readOnly
                    value={publicKeyLine}
                    rows={2}
                    onFocus={(e) => e.currentTarget.select()}
                    className="w-full rounded border border-neutral-300 dark:border-neutral-700 bg-neutral-50 dark:bg-neutral-800 px-3 py-2 font-mono text-xs focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-500"
                  />
                </div>
              )}
              <input type="hidden" name="sshPrivateKeyPem" value={privateKeyPem} />
            </>
          )}
        </div>
      </div>

      {state.error && <p className="text-sm text-red-700 font-medium">{state.error}</p>}
      {state.success && (
        <div className="space-y-1">
          <p className="text-sm text-green-700 font-medium">登録しました。</p>
          {state.connectionCheckStatus === "SUCCESS" && (
            <p className="text-sm text-green-700">疎通確認: 成功しました。</p>
          )}
          {state.connectionCheckStatus === "FAILED" && (
            <p className="text-sm text-amber-800 font-medium">
              疎通確認: 失敗しました。認証情報が正しいか確認してください(登録自体は完了しています)。
            </p>
          )}
        </div>
      )}
      <button
        type="submit"
        disabled={pending}
        className="rounded bg-neutral-900 px-4 py-2 text-sm text-white hover:bg-neutral-800 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-500 disabled:bg-neutral-300 disabled:text-neutral-600"
      >
        {pending ? "登録中…" : "登録"}
      </button>
    </form>
  );
}

function Field({
  name,
  label,
  placeholder,
  type = "text",
  wide = false,
  required = true,
}: {
  name: string;
  label: string;
  placeholder?: string;
  type?: string;
  wide?: boolean;
  required?: boolean;
}) {
  return (
    <label className={`flex flex-col gap-1 text-sm ${wide ? "sm:col-span-2" : ""}`}>
      <span className="text-neutral-700 dark:text-neutral-300 font-medium">{label}</span>
      <input
        name={name}
        type={type}
        placeholder={placeholder}
        required={required}
        className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-500"
      />
    </label>
  );
}
