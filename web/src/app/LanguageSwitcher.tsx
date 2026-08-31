"use client";

import { locales, type Locale } from "@/lib/i18nConfig";
import { useI18n } from "./I18nProvider";

const LOCALE_LABELS: Record<Locale, string> = {
  ja: "日本語",
  en: "English",
};

/**
 * 表示言語を切り替えるセレクトボックス。
 *
 * <p>issue #800: 以前はこのコンポーネント自身が`useEffect`内で`localStorage`を読み、
 * `setLocaleState`を同期的に呼んでいた(`react-hooks/set-state-in-effect`)。さらに
 * 保存値を検証せず`Locale`へキャストしていたため、不正な値(例: `zz`)が入っていると
 * `I18nProvider`は検証済みで`ja`にフォールバックする一方、こちらは`<select value="zz">`と
 * なってどの`<option>`にも一致せず、「本文は日本語なのにセレクトは未選択」という
 * 不整合になっていた。
 *
 * <p>locale の解決は`I18nProvider`(issue #721で`useSyncExternalStore`へ移行済み)に一本化し、
 * ここでは`useI18n()`が返す解決済みの値を表示するだけにする。これにより検証ロジックの
 * 二重管理が無くなり、`eslint-disable`も不要になる。ハイドレーション不一致を避けるための
 * `mounted`ガードも、`I18nProvider`のサーバー用スナップショットが初回HTMLと一致することで
 * 不要になったため外している。
 */
/**
 * ページ再読み込みの継ぎ目。jsdomの`window.location`は再定義もメソッドの差し替えも
 * できないため、テストから差し替えられるようにここへ1段挟む。実行時の挙動は
 * `window.location.reload()`を直接呼ぶのと同じ。
 */
export const pageReloader = {
  reload: () => window.location.reload(),
};

export function LanguageSwitcher() {
  const { locale } = useI18n();

  const handleLanguageChange = (newLocale: string) => {
    localStorage.setItem("locale", newLocale);
    // I18nProviderは購読を行わないno-opストアのため、保存しただけでは再レンダーされない。
    // 表示言語の切り替えはページごと作り直すことで反映する(挙動は変更前と同じ)。
    pageReloader.reload();
  };

  return (
    <select
      value={locale}
      onChange={(e) => handleLanguageChange(e.target.value)}
      aria-label="言語選択"
      className="text-sm border border-neutral-200 rounded px-2 py-1 bg-white dark:bg-neutral-900 dark:border-neutral-700 text-neutral-900 dark:text-neutral-50"
    >
      {locales.map((value) => (
        <option key={value} value={value}>
          {LOCALE_LABELS[value]}
        </option>
      ))}
    </select>
  );
}
