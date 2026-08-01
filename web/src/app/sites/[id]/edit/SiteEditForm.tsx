"use client";

import { useActionState, useState, useTransition } from "react";
import type { SiteDetail } from "@/lib/apiClient";
import { generateSshKeyPairAction } from "../../actions";
import { updateSiteAction, UpdateSiteState } from "./actions";
import { InstallWpCliButton } from "./InstallWpCliButton";

const initialState: UpdateSiteState = {};

export function SiteEditForm({ site }: { site: SiteDetail }) {
  const action = (prevState: UpdateSiteState, formData: FormData) => updateSiteAction(site.id, prevState, formData);
  const [state, formAction, pending] = useActionState(action, initialState);
  const [sshEnabled, setSshEnabled] = useState(site.sshConfigured);
  const [privateKeyPem, setPrivateKeyPem] = useState("");
  const [publicKeyLine, setPublicKeyLine] = useState("");
  const [keyGenError, setKeyGenError] = useState<string | null>(null);
  const [keyGenPending, startKeyGenTransition] = useTransition();

  function handleGenerateKeyPair() {
    setKeyGenError(null);
    startKeyGenTransition(async () => {
      const result = await generateSshKeyPairAction(site.siteKey);
      if (result.error) {
        setKeyGenError(result.error);
        return;
      }
      setPrivateKeyPem(result.privateKeyPem ?? "");
      setPublicKeyLine(result.publicKeyLine ?? "");
    });
  }

  const isSecretConfigured = (name: string) => site.configuredSecretFields.includes(name);

  return (
    <form action={formAction} className="max-w-xl space-y-4 rounded-lg border border-neutral-200 bg-white p-5">
      <label className="flex flex-col gap-1 text-sm">
        <span className="text-neutral-600">サイトキー</span>
        <input
          value={site.siteKey}
          disabled
          className="rounded border border-neutral-300 bg-neutral-50 px-3 py-2 text-sm text-neutral-500"
        />
      </label>

      <label className="flex flex-col gap-1 text-sm">
        <span className="text-neutral-600">表示名</span>
        <input
          name="name"
          defaultValue={site.name}
          className="rounded border border-neutral-300 px-3 py-2 text-sm"
        />
      </label>

      {site.managedWordpress ? (
        <p className="text-sm text-neutral-500">
          自動構築されたWordPressサイトのため、URL・認証情報は編集できません(表示名のみ編集可能です)。
        </p>
      ) : (
        <fieldset className="space-y-3">
          <legend className="text-sm font-medium text-neutral-600">
            認証情報の変更(空欄のままなら変更されません)
          </legend>
          {site.cmsType === "WORDPRESS" ? (
            <div className="space-y-3">
              <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                <Field
                  name="baseUrl"
                  label="WordPressのURL"
                  placeholder="変更する場合のみ入力"
                  defaultValue={site.credentials.baseUrl}
                />
                <Field
                  name="username"
                  label="WordPressユーザー名"
                  placeholder="変更する場合のみ入力"
                  defaultValue={site.credentials.username}
                />
                <Field
                  name="appPassword"
                  label="アプリケーションパスワード"
                  placeholder="変更する場合のみ入力"
                  type="password"
                  wide
                  configured={isSecretConfigured("appPassword")}
                />
              </div>

              <label className="flex items-center gap-2 text-sm text-neutral-600">
                <input
                  type="checkbox"
                  checked={sshEnabled}
                  onChange={(e) => setSshEnabled(e.target.checked)}
                />
                SSH経由(wp-cli)での接続に切り替える/接続情報を変更する
              </label>

              {sshEnabled && (
                <div className="space-y-3 rounded border border-neutral-200 p-3">
                  <input type="hidden" name="transport" value="SSH" />
                  <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                    <Field
                      name="sshHost"
                      label="SSHホスト"
                      placeholder="変更する場合のみ入力"
                      defaultValue={site.credentials.sshHost}
                    />
                    <Field
                      name="sshPort"
                      label="SSHポート(既定22)"
                      placeholder="変更する場合のみ入力"
                      defaultValue={site.credentials.sshPort}
                    />
                    <Field
                      name="sshUser"
                      label="SSHユーザー"
                      placeholder="変更する場合のみ入力"
                      defaultValue={site.credentials.sshUser}
                    />
                    <Field
                      name="wpPath"
                      label="WordPressインストール先ディレクトリ(wp-cliの--path)"
                      placeholder="例: /home/deploy/public_html(wp-cli本体のパスではありません)"
                      defaultValue={site.credentials.wpPath}
                      wide
                    />
                    <Field
                      name="sshHostKeyFingerprint"
                      label="ホスト鍵fingerprint(上級者向け・通常は空欄)"
                      placeholder="変更する場合のみ入力"
                      defaultValue={site.credentials.sshHostKeyFingerprint}
                      wide
                    />
                  </div>

                  <div className="space-y-2">
                    <button
                      type="button"
                      onClick={handleGenerateKeyPair}
                      disabled={keyGenPending}
                      className="rounded bg-neutral-100 px-3 py-1.5 text-sm text-neutral-700 disabled:opacity-50"
                    >
                      {keyGenPending ? "鍵ペアを生成中…" : "SSH鍵ペアを再生成"}
                    </button>
                    {isSecretConfigured("sshPrivateKeyPem") && !publicKeyLine && (
                      <p className="text-xs text-green-600">SSH秘密鍵は設定済みです。</p>
                    )}
                    {keyGenError && <p className="text-sm text-red-600">{keyGenError}</p>}
                    {publicKeyLine && (
                      <div className="space-y-1">
                        <p className="text-sm text-neutral-600">
                          以下の公開鍵をリモートサーバーの対象ユーザーの<code>~/.ssh/authorized_keys</code>
                          へ手動で追記してから保存してください。
                        </p>
                        <textarea
                          readOnly
                          value={publicKeyLine}
                          rows={2}
                          onFocus={(e) => e.currentTarget.select()}
                          className="w-full rounded border border-neutral-300 bg-neutral-50 px-3 py-2 font-mono text-xs"
                        />
                      </div>
                    )}
                    <input type="hidden" name="sshPrivateKeyPem" value={privateKeyPem} />
                  </div>
                </div>
              )}

              {site.sshConfigured && (
                <div className="rounded border border-neutral-200 p-3">
                  <p className="mb-2 text-sm text-neutral-600">
                    SSH接続が設定されています。wp-cliが未インストールの場合はここからインストールできます。
                  </p>
                  <InstallWpCliButton id={site.id} />
                </div>
              )}
            </div>
          ) : (
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
              <Field
                name="serviceId"
                label="Service ID"
                placeholder="変更する場合のみ入力"
                defaultValue={site.credentials.serviceId}
              />
              <Field
                name="apiKey"
                label="API Key"
                placeholder="変更する場合のみ入力"
                type="password"
                configured={isSecretConfigured("apiKey")}
              />
              <Field
                name="managementApiKey"
                label="Management API Key"
                placeholder="変更する場合のみ入力"
                type="password"
                configured={isSecretConfigured("managementApiKey")}
              />
              <Field
                name="postsEndpoint"
                label="投稿用エンドポイント"
                placeholder="変更する場合のみ入力"
                defaultValue={site.credentials.postsEndpoint}
              />
              <Field
                name="categoriesEndpoint"
                label="カテゴリ用エンドポイント"
                placeholder="変更する場合のみ入力"
                defaultValue={site.credentials.categoriesEndpoint}
              />
              <Field
                name="tagsEndpoint"
                label="タグ用エンドポイント"
                placeholder="変更する場合のみ入力"
                defaultValue={site.credentials.tagsEndpoint}
              />
            </div>
          )}
        </fieldset>
      )}

      {state.error && <p className="text-sm text-red-600">{state.error}</p>}
      {state.success && (
        <div className="space-y-1">
          <p className="text-sm text-green-600">保存しました。</p>
          {state.connectionCheckStatus === "SUCCESS" && (
            <p className="text-sm text-green-600">疎通確認: 成功しました。</p>
          )}
          {state.connectionCheckStatus === "FAILED" && (
            <p className="text-sm text-amber-700">疎通確認: 失敗しました。認証情報を確認してください。</p>
          )}
        </div>
      )}

      <button
        type="submit"
        disabled={pending}
        className="rounded bg-neutral-900 px-4 py-2 text-sm text-white disabled:bg-neutral-200 disabled:text-neutral-600"
      >
        {pending ? "保存中…" : "保存"}
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
  defaultValue,
  configured = false,
}: {
  name: string;
  label: string;
  placeholder?: string;
  type?: string;
  wide?: boolean;
  defaultValue?: string;
  configured?: boolean;
}) {
  return (
    <label className={`flex flex-col gap-1 text-sm ${wide ? "sm:col-span-2" : ""}`}>
      <span className="text-neutral-600">
        {label}
        {configured && <span className="ml-1 text-xs text-green-600">(設定済み)</span>}
      </span>
      <input
        name={name}
        type={type}
        defaultValue={defaultValue}
        placeholder={placeholder}
        className="rounded border border-neutral-300 px-3 py-2 text-sm"
      />
    </label>
  );
}
