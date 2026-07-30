"use client";

import { useActionState } from "react";
import type { Site } from "@/lib/apiClient";
import { updateSiteAction, UpdateSiteState } from "./actions";

const initialState: UpdateSiteState = {};

export function SiteEditForm({ site }: { site: Site }) {
  const action = (prevState: UpdateSiteState, formData: FormData) => updateSiteAction(site.id, prevState, formData);
  const [state, formAction, pending] = useActionState(action, initialState);

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
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
              <Field name="baseUrl" label="WordPressのURL" placeholder="変更する場合のみ入力" />
              <Field name="username" label="WordPressユーザー名" placeholder="変更する場合のみ入力" />
              <Field
                name="appPassword"
                label="アプリケーションパスワード"
                placeholder="変更する場合のみ入力"
                type="password"
                wide
              />
            </div>
          ) : (
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
              <Field name="serviceId" label="Service ID" placeholder="変更する場合のみ入力" />
              <Field name="apiKey" label="API Key" placeholder="変更する場合のみ入力" type="password" />
              <Field name="managementApiKey" label="Management API Key" placeholder="変更する場合のみ入力" type="password" />
              <Field name="postsEndpoint" label="投稿用エンドポイント" placeholder="変更する場合のみ入力" />
              <Field name="categoriesEndpoint" label="カテゴリ用エンドポイント" placeholder="変更する場合のみ入力" />
              <Field name="tagsEndpoint" label="タグ用エンドポイント" placeholder="変更する場合のみ入力" />
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
        className="rounded border border-neutral-300 px-3 py-2 text-sm"
      />
    </label>
  );
}
