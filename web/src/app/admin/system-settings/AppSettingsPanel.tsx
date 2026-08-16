"use client";

import { useActionState } from "react";
import type { AppSetting } from "@/lib/apiClient";
import { updateAppSettingsAction, type UpdateAppSettingsFormState } from "./actions";

const initialState: UpdateAppSettingsFormState = {};

const GROUPS: { title: string; description: string; keys: string[] }[] = [
  {
    title: "外部LLMサービス連携",
    description: "下書き/校正/要約支援・タグ提案・記事プランニングに使用するOpenAI互換のLLM APIの接続設定です。",
    keys: ["llm_api_key", "llm_base_url", "llm_model", "llm_available_models", "llm_request_timeout_seconds"],
  },
  {
    title: "メール送信",
    description: "パスワード再設定メール等の送信に使用するSMTPサーバーの接続設定です。",
    keys: ["mail_host", "mail_port", "mail_username", "mail_password", "app_mail_from"],
  },
  {
    title: "Google OAuthクライアント",
    description: "Google AdSense連携で使用する、アプリ全体で1つのGoogle OAuthクライアントの設定です。",
    keys: ["google_oauth_client_id", "google_oauth_client_secret"],
  },
  {
    title: "Webフロントの公開URL",
    description: "パスワード再設定メール内のリンク生成等に使用する、Web管理画面の公開URLです。",
    keys: ["app_web_base_url"],
  },
];

function sourceLabel(source: AppSetting["source"]): string {
  switch (source) {
    case "DATABASE":
      return "DB設定を使用中";
    case "ENVIRONMENT":
      return "環境変数を使用中";
    default:
      return "未設定";
  }
}

function SettingField({ setting }: { setting: AppSetting }) {
  return (
    <label className="flex flex-col gap-1 text-sm">
      <span className="flex items-center justify-between text-neutral-600 dark:text-neutral-400">
        <span>{setting.label}</span>
        <span
          className={
            setting.configured
              ? "text-xs text-neutral-500 dark:text-neutral-400"
              : "text-xs text-neutral-400 dark:text-neutral-600"
          }
        >
          {sourceLabel(setting.source)}
        </span>
      </span>
      <input
        name={setting.key}
        type={setting.secret ? "password" : "text"}
        defaultValue={setting.secret ? "" : setting.value ?? ""}
        placeholder={setting.secret ? (setting.configured ? "設定済み(変更する場合のみ入力)" : "未設定") : undefined}
        autoComplete="off"
        className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm"
      />
    </label>
  );
}

/**
 * adminユーザー限定のシステム設定画面(issue #403)。全項目を1つのフォームとしてまとめて送信する。
 * 空欄のまま送信した項目は「未設定に戻す(環境変数へフォールバック)」として扱われるため、秘匿情報の
 * 項目は変更する場合のみ入力する(既存の設定はそのまま維持される)。
 */
export function AppSettingsPanel({ settings }: { settings: AppSetting[] }) {
  const [state, formAction, pending] = useActionState(updateAppSettingsAction, initialState);
  const byKey = new Map(settings.map((setting) => [setting.key, setting]));

  return (
    <form action={formAction} className="space-y-8">
      {GROUPS.map((group) => (
        <section
          key={group.title}
          className="space-y-4 rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-5"
        >
          <div>
            <h2 className="font-medium">{group.title}</h2>
            <p className="text-sm text-neutral-600 dark:text-neutral-400">{group.description}</p>
          </div>
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            {group.keys
              .map((key) => byKey.get(key))
              .filter((setting): setting is AppSetting => setting !== undefined)
              .map((setting) => (
                <SettingField key={setting.key} setting={setting} />
              ))}
          </div>
        </section>
      ))}

      <div className="flex items-center gap-3">
        <button
          type="submit"
          disabled={pending}
          className="rounded bg-neutral-900 px-4 py-2 text-sm text-white disabled:bg-neutral-200 disabled:text-neutral-600"
        >
          {pending ? "保存中…" : "まとめて保存"}
        </button>
        {state.error && <p className="text-sm text-red-600">{state.error}(この保存操作での変更は反映されていません)</p>}
        {state.success && <p className="text-sm text-green-600">保存しました。</p>}
      </div>
    </form>
  );
}
