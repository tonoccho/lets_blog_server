"use client";

import { useActionState, useState } from "react";
import { useRef, useEffect } from "react";
import { registerSiteAction, RegisterSiteState } from "./actions";

const initialState: RegisterSiteState = {};

type CmsType = "WORDPRESS" | "MICROCMS";

export function SiteForm() {
  const [state, formAction, pending] = useActionState(registerSiteAction, initialState);
  const [cmsType, setCmsType] = useState<CmsType>("WORDPRESS");
  const formRef = useRef<HTMLFormElement>(null);

  useEffect(() => {
    if (state.success) {
      formRef.current?.reset();
      setCmsType("WORDPRESS");
    }
  }, [state.success]);

  return (
    <form ref={formRef} action={formAction} className="space-y-3 rounded-lg border border-neutral-200 bg-white p-5">
      <h2 className="font-medium">サイトを登録</h2>
      <p className="text-sm text-neutral-600">
        サイト登録は既存のWordPress/microCMSサイトの認証情報を保存するだけです。サーバー側で新規にサイトや
        リソースを作成する「プロビジョニング」は行いません。登録時に入力内容で疎通確認を行いますが、
        失敗した場合も登録自体は完了します(後から認証情報を見直してください)。
      </p>
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-neutral-600">CMS種別</span>
          <select
            name="cmsType"
            value={cmsType}
            onChange={(e) => setCmsType(e.target.value as CmsType)}
            className="rounded border border-neutral-300 px-3 py-2 text-sm"
          >
            <option value="WORDPRESS">WordPress</option>
            <option value="MICROCMS">microCMS</option>
          </select>
        </label>
        <Field name="name" label="表示名" placeholder="My Blog" />
        <Field name="siteKey" label="サイトキー" placeholder="main" />
      </div>

      {cmsType === "WORDPRESS" && (
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

      {state.error && <p className="text-sm text-red-600">{state.error}</p>}
      {state.success && (
        <div className="space-y-1">
          <p className="text-sm text-green-600">登録しました。</p>
          {state.connectionCheckStatus === "SUCCESS" && (
            <p className="text-sm text-green-600">疎通確認: 成功しました。</p>
          )}
          {state.connectionCheckStatus === "FAILED" && (
            <p className="text-sm text-amber-700">
              疎通確認: 失敗しました。認証情報が正しいか確認してください(登録自体は完了しています)。
            </p>
          )}
        </div>
      )}
      <button
        type="submit"
        disabled={pending}
        className="rounded bg-neutral-900 px-4 py-2 text-sm text-white disabled:bg-neutral-200 disabled:text-neutral-600"
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
}: {
  name: string;
  label: string;
  placeholder?: string;
  type?: string;
  wide?: boolean;
}) {
  return (
    <label className={`flex flex-col gap-1 text-sm ${wide ? "sm:col-span-2" : ""}`}>
      <span className="text-neutral-600">{label}</span>
      <input
        name={name}
        type={type}
        placeholder={placeholder}
        required
        className="rounded border border-neutral-300 px-3 py-2 text-sm"
      />
    </label>
  );
}
