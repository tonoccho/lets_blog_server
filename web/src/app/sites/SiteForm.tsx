"use client";

import { useActionState, useState, useTransition } from "react";
import { useRef, useEffect } from "react";
import { generateSshKeyPairAction, registerSiteAction, RegisterSiteState } from "./actions";

const initialState: RegisterSiteState = {};

type CmsType = "WORDPRESS" | "MICROCMS";
type Transport = "REST" | "SSH";

export function SiteForm() {
  const [state, formAction, pending] = useActionState(registerSiteAction, initialState);
  const [cmsType, setCmsType] = useState<CmsType>("WORDPRESS");
  const [transport, setTransport] = useState<Transport>("REST");
  const [privateKeyPem, setPrivateKeyPem] = useState("");
  const [publicKeyLine, setPublicKeyLine] = useState("");
  const [keyGenError, setKeyGenError] = useState<string | null>(null);
  const [keyGenPending, startKeyGenTransition] = useTransition();
  const formRef = useRef<HTMLFormElement>(null);

  useEffect(() => {
    if (state.success) {
      formRef.current?.reset();
      setCmsType("WORDPRESS");
      setTransport("REST");
      setPrivateKeyPem("");
      setPublicKeyLine("");
      setKeyGenError(null);
    }
  }, [state.success]);

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
    <form ref={formRef} action={formAction} className="space-y-3 rounded-lg border border-neutral-200 bg-white p-5">
      <h2 className="font-medium">サイトを登録</h2>
      <p className="text-sm text-neutral-700">
        サイト登録は既存のWordPress/microCMSサイトの認証情報を保存するだけです。サーバー側で新規にサイトや
        リソースを作成する「プロビジョニング」は行いません。登録時に入力内容で疎通確認を行いますが、
        失敗した場合も登録自体は完了します(後から認証情報を見直してください)。
      </p>
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-neutral-700 font-medium">CMS種別</span>
          <select
            name="cmsType"
            value={cmsType}
            onChange={(e) => setCmsType(e.target.value as CmsType)}
            className="rounded border border-neutral-300 px-3 py-2 text-sm focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-500"
          >
            <option value="WORDPRESS">WordPress</option>
            <option value="MICROCMS">microCMS</option>
          </select>
        </label>
        <Field name="name" label="表示名" placeholder="My Blog" />
        <Field name="siteKey" label="サイトキー" placeholder="main" />
      </div>

      {cmsType === "WORDPRESS" && (
        <div className="space-y-3">
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-neutral-700 font-medium">接続方式</span>
            <select
              name="transport"
              value={transport}
              onChange={(e) => setTransport(e.target.value as Transport)}
              className="rounded border border-neutral-300 px-3 py-2 text-sm sm:max-w-xs focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-500"
            >
              <option value="REST">REST API(通常はこちら)</option>
              <option value="SSH">SSH経由(wp-cli。REST APIがブロックされているサイト向け)</option>
            </select>
          </label>

          {transport === "REST" ? (
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
              <Field name="baseUrl" label="WordPressのURL" placeholder="https://example.com" />
              <Field name="username" label="WordPressユーザー名" placeholder="admin" />
              <Field
                name="appPassword"
                label="アプリケーションパスワード"
                placeholder="xxxx xxxx xxxx xxxx xxxx xxxx"
                type="password"
                wide
              />
            </div>
          ) : (
            <div className="space-y-3 rounded border border-neutral-200 p-3">
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
                <button
                  type="button"
                  onClick={handleGenerateKeyPair}
                  disabled={keyGenPending}
                  className="rounded bg-neutral-100 px-3 py-1.5 text-sm text-neutral-900 hover:bg-neutral-200 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-500 disabled:opacity-50"
                >
                  {keyGenPending ? "鍵ペアを生成中…" : "SSH鍵ペアを生成"}
                </button>
                {keyGenError && <p className="text-sm text-red-600">{keyGenError}</p>}
                {publicKeyLine && (
                  <div className="space-y-1">
                    <p className="text-sm text-neutral-700">
                      以下の公開鍵をリモートサーバーの対象ユーザーの<code>~/.ssh/authorized_keys</code>
                      へ手動で追記してから登録してください。
                    </p>
                    <textarea
                      readOnly
                      value={publicKeyLine}
                      rows={2}
                      onFocus={(e) => e.currentTarget.select()}
                      className="w-full rounded border border-neutral-300 bg-neutral-50 px-3 py-2 font-mono text-xs focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-500"
                    />
                  </div>
                )}
                <input type="hidden" name="sshPrivateKeyPem" value={privateKeyPem} />
              </div>
            </div>
          )}
        </div>
      )}

      {cmsType === "MICROCMS" && (
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <Field name="serviceId" label="Service ID" placeholder="my-service" />
          <Field name="apiKey" label="API Key" placeholder="xxxxxxxxxxxxxxxx" type="password" />
          <Field name="managementApiKey" label="Management API Key" placeholder="xxxxxxxxxxxxxxxx" type="password" />
          <Field name="postsEndpoint" label="投稿用エンドポイント" placeholder="posts" />
          <Field name="categoriesEndpoint" label="カテゴリ用エンドポイント" placeholder="categories" />
          <Field name="tagsEndpoint" label="タグ用エンドポイント" placeholder="tags" />
        </div>
      )}

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
      <span className="text-neutral-700 font-medium">{label}</span>
      <input
        name={name}
        type={type}
        placeholder={placeholder}
        required={required}
        className="rounded border border-neutral-300 px-3 py-2 text-sm focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-500"
      />
    </label>
  );
}
