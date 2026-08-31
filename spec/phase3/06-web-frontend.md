# 06. Web 管理フロントエンドの拡張(CMS種別動的化)

## 目的

Phase2 までのサイト登録UI は「WordPress専用」で硬い設計になっており、フォーム・Server Action・API Client のあらゆる層がWordPress固有フィールド(`wpUsername`, `wpAppPassword`)を想定している。本タスクでは、Web UIに「CMS種別選択」セレクトボックスを追加し、選択に応じて表示するフォームフィールドを動的に切り替える。あわせて Server Action・API Client の型定義を汎用化する。

## 前提・決定事項

- クライアント側フレームワークは Next.js (App Router) + Tailwind CSS v4
- Server Action は引き続き使用(フォーム送信を処理)
- CMS種別セレクト(WordPress / microCMS)で選択が変わるたび、必須入力フィールドの集合が動的に変わる
- API側の `SiteRegisterRequest` DTO が `(name, siteKey, cmsType, credentials: Map<String,String>)` に汎用化されているため、Web 側もその構造に合わせて request を構築する

## コンポーネント構成・変更内容

### `web/src/lib/apiClient.ts` (修正)

```typescript
// 型定義の汎用化
export interface Site {
  id: number;
  name: string;
  siteKey: string;
  cmsType: "WORDPRESS" | "MICROCMS";
  baseUrl: string;
  createdAt: string;
  updatedAt: string;
}

export interface PostSummary {
  id: number;
  siteId: number;
  siteName: string;
  wpPostId: string;  // Long → String に変更
  slug: string | null;
  status: string;
  lastPublishedAt: string | null;
}

export interface SiteRegisterInput {
  name: string;
  siteKey: string;
  cmsType: "WORDPRESS" | "MICROCMS";
  credentials: Record<string, string>;  // CMS種別ごとの可変フィールド
}

export function registerSite(input: SiteRegisterInput): Promise<Site> {
  return apiFetch<Site>('/api/sites', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
}
```

### `web/src/app/sites/actions.ts` (修正)

```typescript
"use server";

import { revalidatePath } from "next/cache";
import { registerSite } from "@/lib/apiClient";

export interface RegisterSiteState {
  error?: string;
  success?: boolean;
}

const WORDPRESS_FIELDS = ["name", "siteKey", "baseUrl", "username", "appPassword"];
const MICROCMS_FIELDS = ["name", "siteKey", "serviceId", "apiKey", "managementApiKey", "postsEndpoint", "categoriesEndpoint", "tagsEndpoint"];

export async function registerSiteAction(
  _prevState: RegisterSiteState,
  formData: FormData
): Promise<RegisterSiteState> {
  const cmsType = String(formData.get("cmsType") ?? "").trim();
  
  // CMS種別に応じた必須項目チェック
  const requiredFields = cmsType === "WORDPRESS" ? WORDPRESS_FIELDS : MICROCMS_FIELDS;
  for (const field of requiredFields) {
    const value = String(formData.get(field) ?? "").trim();
    if (!value) {
      return { error: `${field} は必須です。` };
    }
  }

  try {
    const credentials: Record<string, string> = {};
    if (cmsType === "WORDPRESS") {
      credentials["baseUrl"] = String(formData.get("baseUrl") ?? "").trim();
      credentials["username"] = String(formData.get("username") ?? "").trim();
      credentials["appPassword"] = String(formData.get("appPassword") ?? "").trim();
    } else if (cmsType === "MICROCMS") {
      credentials["serviceId"] = String(formData.get("serviceId") ?? "").trim();
      credentials["apiKey"] = String(formData.get("apiKey") ?? "").trim();
      credentials["managementApiKey"] = String(formData.get("managementApiKey") ?? "").trim();
      credentials["postsEndpoint"] = String(formData.get("postsEndpoint") ?? "").trim();
      credentials["categoriesEndpoint"] = String(formData.get("categoriesEndpoint") ?? "").trim();
      credentials["tagsEndpoint"] = String(formData.get("tagsEndpoint") ?? "").trim();
    }

    await registerSite({
      name: String(formData.get("name") ?? "").trim(),
      siteKey: String(formData.get("siteKey") ?? "").trim(),
      cmsType: cmsType as "WORDPRESS" | "MICROCMS",
      credentials,
    });
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }

  revalidatePath("/sites");
  return { success: true };
}
```

### `web/src/app/sites/SiteForm.tsx` (修正・大幅)

```typescript
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

  const isWordPress = cmsType === "WORDPRESS";

  return (
    <form ref={formRef} action={formAction} className="space-y-3 rounded-lg border border-neutral-200 bg-white p-5">
      <h2 className="font-medium">サイトを登録</h2>

      {/* CMS種別セレクト */}
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
      </div>

      {/* 共通フィールド */}
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        <Field name="name" label="表示名" placeholder="My Blog" />
        <Field name="siteKey" label="サイトキー" placeholder="main" />
      </div>

      {/* WordPress 固有フィールド */}
      {isWordPress && (
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

      {/* microCMS 固有フィールド */}
      {!isWordPress && (
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <Field name="serviceId" label="microCMS Service ID" placeholder="my-service" />
          <Field name="apiKey" label="API Key" placeholder="..." type="password" />
          <Field name="managementApiKey" label="Management API Key" placeholder="..." type="password" />
          <Field name="postsEndpoint" label="Posts Endpoint" placeholder="posts" wide />
          <Field name="categoriesEndpoint" label="Categories Endpoint" placeholder="categories" />
          <Field name="tagsEndpoint" label="Tags Endpoint" placeholder="tags" />
        </div>
      )}

      {/* エラー・成功メッセージ */}
      {state.error && <p className="text-sm text-red-600">{state.error}</p>}
      {state.success && <p className="text-sm text-green-600">登録しました。</p>}

      <button
        type="submit"
        disabled={pending}
        className="rounded bg-neutral-900 px-4 py-2 text-sm text-white disabled:opacity-50"
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
```

重要な点:
- `cmsType` state で選択中のCMS種別を管理
- `isWordPress` フラグで条件分岐、表示するフィールドセットを切り替え
- 既存の `Field` コンポーネントは再利用

### `web/src/app/sites/page.tsx` (修正)

既存の一覧表示に CMS種別カラムを追加:

```typescript
export async function SitesPage() {
  const sites = await listSites();

  return (
    <div className="space-y-4">
      <SiteForm />
      <div className="rounded-lg border border-neutral-200 bg-white p-4">
        <h2 className="mb-3 font-medium">登録済みサイト</h2>
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-neutral-200 text-left">
                <th className="px-3 py-2 font-medium">サイトキー</th>
                <th className="px-3 py-2 font-medium">表示名</th>
                <th className="px-3 py-2 font-medium">CMS種別</th>
                <th className="px-3 py-2 font-medium">URL</th>
                <th className="px-3 py-2 font-medium">登録日</th>
              </tr>
            </thead>
            <tbody>
              {sites.map((site) => (
                <tr key={site.id} className="border-b border-neutral-100 hover:bg-neutral-50">
                  <td className="px-3 py-2">{site.siteKey}</td>
                  <td className="px-3 py-2">{site.name}</td>
                  <td className="px-3 py-2">
                    <span className={`inline-block rounded px-2 py-1 text-xs font-medium ${
                      site.cmsType === 'WORDPRESS' ? 'bg-blue-100 text-blue-700' : 'bg-green-100 text-green-700'
                    }`}>
                      {site.cmsType}
                    </span>
                  </td>
                  <td className="px-3 py-2 text-neutral-500">{site.baseUrl}</td>
                  <td className="px-3 py-2 text-neutral-500">{new Date(site.updatedAt).toLocaleString('ja-JP')}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}
```

## タスクチェックリスト

- [x] `apiClient.ts`:
  - [x] `Site` interface に `cmsType` フィールド追加
  - [x] `PostSummary.wpPostId` 型を `number` → `string` に変更
  - [x] `SiteRegisterInput` を汎用化(`cmsType`, `credentials: Record<string,string>`)
- [x] `actions.ts`:
  - [x] CMS種別ごとの必須フィールド定義(`CREDENTIAL_FIELDS: Record<CmsType, string[]>`)
  - [x] `registerSiteAction()` 内で CMS種別に応じた credentials Map 構築
  - [x] CMS種別ごとの必須項目チェック実装
- [x] `SiteForm.tsx`:
  - [x] CMS種別セレクトボックス追加(WordPress / microCMS)
  - [x] 選択変更で `cmsType` state 更新
  - [x] `cmsType === "WORDPRESS"` / `"MICROCMS"` の条件分岐で WordPress / microCMS フィールドセットを表示切り替え
  - [x] 既存 `Field` コンポーネント再利用
  - [x] フォーム送信成功後、`cmsType` を WORDPRESS にリセット
- [x] `page.tsx`:
  - [x] 一覧テーブルに CMS種別カラム追加
  - [x] CMS種別に応じた色分け表示(WordPress: 青 / microCMS: 緑)
- [x] フロントエンド動作確認
  - [x] `tsc --noEmit` / `npm run build` が成功することを確認
  - [x] 開発サーバー起動 + next-auth経由でログインし、`/sites` ページでCMS種別セレクト・既存WordPressサイトのバッジ表示をHTML上で確認

## 実装状況

計画では`isWordPress`真偽値フラグでの分岐としていたが、実装では`cmsType === "WORDPRESS"` / `"MICROCMS"`の直接比較に単純化した(CMS種別が今後3つ以上に増えることを見越すと真偽値フラグより種別直接比較の方が拡張しやすいため)。それ以外は計画通り。

## 未決事項

- ユーザーが誤ったEndpoint名を入力した場合のバリデーション(現状はAPI呼び出し時にエラーになる)
- microCMS のEndpoint名の命名規則に関するドキュメント(運用段階で別途)
