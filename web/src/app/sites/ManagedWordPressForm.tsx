"use client";

import { useActionState, useRef, useEffect } from "react";
import { createManagedWordPressSiteAction, CreateManagedWordPressSiteState } from "./actions";

const initialState: CreateManagedWordPressSiteState = {};

export function ManagedWordPressForm() {
  const [state, formAction, pending] = useActionState(createManagedWordPressSiteAction, initialState);
  const formRef = useRef<HTMLFormElement>(null);

  useEffect(() => {
    if (state.success) {
      formRef.current?.reset();
    }
  }, [state.success]);

  return (
    <form ref={formRef} action={formAction} className="space-y-3 rounded-lg border border-neutral-200 bg-white p-5">
      <h2 className="font-medium">WordPressをこのサーバーに新規構築</h2>
      <p className="text-sm text-neutral-600">
        常駐WordPressコンテナ上にサブディレクトリでWordPressを自動インストールし、
        カテゴリ・タグ・著者の初期設定まで自動で行います。構築完了後は
        <code>https://localhost/sites/&#123;サイトキー&#125;/</code> でアクセスできます。
      </p>
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        <Field name="managedName" label="表示名" placeholder="My Blog" />
        <Field name="managedSiteKey" label="サイトキー(英数字・ハイフン)" placeholder="main" />
        <Field name="managedTitle" label="WordPressサイトタイトル" placeholder="My Blog" wide />
        <Field name="managedAdminUser" label="管理者ユーザー名" placeholder="admin" />
        <Field name="managedAdminEmail" label="管理者メールアドレス" placeholder="admin@example.com" type="email" />
        <Field
          name="managedAdminPassword"
          label="管理者パスワード"
          placeholder="8文字以上"
          type="password"
          wide
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
