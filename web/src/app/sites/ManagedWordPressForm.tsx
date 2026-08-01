"use client";

import { useActionState, useRef, useEffect } from "react";
import type { AppUser, Site } from "@/lib/apiClient";
import { createManagedWordPressSiteAction, CreateManagedWordPressSiteState } from "./actions";

const initialState: CreateManagedWordPressSiteState = {};

const LOCALE_OPTIONS = [
  { value: "ja", label: "日本語" },
  { value: "en_US", label: "English (US)" },
  { value: "en_GB", label: "English (UK)" },
  { value: "zh_CN", label: "中文(简体)" },
  { value: "zh_TW", label: "中文(繁體)" },
  { value: "ko_KR", label: "한국어" },
  { value: "fr_FR", label: "Français" },
  { value: "de_DE", label: "Deutsch" },
  { value: "es_ES", label: "Español" },
  { value: "pt_BR", label: "Português" },
];

function deriveWpUsername(email: string): string {
  const localPart = email.split("@")[0] ?? "";
  return localPart.replace(/[^a-zA-Z0-9._-]/g, "");
}

export function ManagedWordPressForm({ users, templateCandidates }: { users: AppUser[]; templateCandidates: Site[] }) {
  const [state, formAction, pending] = useActionState(createManagedWordPressSiteAction, initialState);
  const formRef = useRef<HTMLFormElement>(null);

  useEffect(() => {
    if (state.success) {
      formRef.current?.reset();
    }
  }, [state.success]);

  const handleUserPick = (e: React.ChangeEvent<HTMLSelectElement>) => {
    const userId = e.target.value;
    if (!userId) return;
    const user = users.find((u) => u.id === Number(userId));
    if (!user || !formRef.current) return;
    const userNameInput = formRef.current.elements.namedItem("managedAdminUser") as HTMLInputElement | null;
    const emailInput = formRef.current.elements.namedItem("managedAdminEmail") as HTMLInputElement | null;
    if (userNameInput) userNameInput.value = deriveWpUsername(user.email);
    if (emailInput) emailInput.value = user.email;
  };

  return (
    <form ref={formRef} action={formAction} className="space-y-3 rounded-lg border border-neutral-200 bg-white p-5">
      <h2 className="font-medium">WordPressをこのサーバーに新規構築</h2>
      <p className="text-sm text-neutral-600">
        常駐WordPressコンテナ上にサブディレクトリでWordPressを自動インストールし、
        カテゴリ・タグ・著者の初期設定まで自動で行います。構築完了後は
        <code>https://localhost/sites/&#123;サイトキー&#125;/</code> でアクセスできます。
        テンプレートサイトを選択した場合、テーマ・プラグイン・メディア・投稿等のコンテンツを
        複製するため、DB・メディアの量に応じて構築にさらに時間がかかることがあります。
      </p>
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        <Field name="managedName" label="表示名" placeholder="My Blog" />
        <Field name="managedSiteKey" label="サイトキー(英数字・ハイフン)" placeholder="main" />
        <Field name="managedTitle" label="WordPressサイトタイトル" placeholder="My Blog" wide />
        <label className="flex flex-col gap-1 text-sm sm:col-span-2">
          <span className="text-neutral-600">テンプレートサイト(任意)</span>
          <select
            name="managedTemplateSiteId"
            defaultValue=""
            className="rounded border border-neutral-300 px-3 py-2 text-sm"
          >
            <option value="">なし(空のWordPressから始める)</option>
            {templateCandidates.map((site) => (
              <option key={site.id} value={site.id}>
                {site.name}({site.siteKey})
              </option>
            ))}
          </select>
        </label>
        <label className="flex flex-col gap-1 text-sm sm:col-span-2">
          <span className="text-neutral-600">サーバー登録ユーザーから選択(任意)</span>
          <select
            onChange={handleUserPick}
            defaultValue=""
            className="rounded border border-neutral-300 px-3 py-2 text-sm"
          >
            <option value="">選択してください</option>
            {users.map((u) => (
              <option key={u.id} value={u.id}>
                {u.email}
              </option>
            ))}
          </select>
        </label>
        <Field name="managedAdminUser" label="管理者ユーザー名" placeholder="admin" />
        <Field name="managedAdminEmail" label="管理者メールアドレス" placeholder="admin@example.com" type="email" />
        <Field
          name="managedAdminPassword"
          label="管理者パスワード"
          placeholder="8文字以上"
          type="password"
          wide
        />
        <Field
          name="managedLocale"
          label="WordPress言語"
          isSelect
          options={LOCALE_OPTIONS}
          defaultValue="ja"
        />
      </div>
      {state.error && <p className="text-sm text-red-600">{state.error}</p>}
      {state.success && <p className="text-sm text-green-600">構築しました。</p>}
      <button
        type="submit"
        disabled={pending}
        className="rounded bg-neutral-900 px-4 py-2 text-sm text-white disabled:bg-neutral-200 disabled:text-neutral-600"
      >
        {pending ? "構築中(数分かかる場合があります)…" : "構築する"}
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
  isSelect = false,
  options,
  defaultValue,
}: {
  name: string;
  label: string;
  placeholder?: string;
  type?: string;
  wide?: boolean;
  isSelect?: boolean;
  options?: { value: string; label: string }[];
  defaultValue?: string;
}) {
  if (isSelect) {
    return (
      <label className={`flex flex-col gap-1 text-sm ${wide ? "sm:col-span-2" : ""}`}>
        <span className="text-neutral-600">{label}</span>
        <select
          name={name}
          defaultValue={defaultValue}
          required
          className="rounded border border-neutral-300 px-3 py-2 text-sm"
        >
          {options?.map((opt) => (
            <option key={opt.value} value={opt.value}>
              {opt.label}
            </option>
          ))}
        </select>
      </label>
    );
  }

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
