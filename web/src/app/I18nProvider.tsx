"use client";

import { createContext, useContext, ReactNode, useSyncExternalStore } from "react";
import type { Locale } from "@/lib/i18nConfig";
import messages_ja from "../../messages/ja.json";
import messages_en from "../../messages/en.json";

export type Messages = typeof messages_ja;

interface I18nContextType {
  locale: Locale;
  messages: Messages;
  t: (namespace: keyof Messages, key: string) => string;
}

const I18nContext = createContext<I18nContextType | undefined>(undefined);

const messagesByLocale: Record<Locale, Messages> = {
  ja: messages_ja,
  en: messages_en,
};

const LOCALE_STORAGE_KEY = "locale";

/**
 * localStorageの変更を購読する。同じタブ内での変更は{@link LanguageSwitcher}が
 * `window.location.reload()` するため、ここで拾うのは他タブでの変更(storageイベント)。
 */
function subscribeToStoredLocale(onStoreChange: () => void): () => void {
  window.addEventListener("storage", onStoreChange);
  return () => window.removeEventListener("storage", onStoreChange);
}

/**
 * クライアント側のスナップショット。Reactは`Object.is`で前回値と比較するため、
 * 文字列またはnullを返すこの実装は安定している(新しいオブジェクトを作らない)。
 * 読み取り不可の環境(プライベートブラウジング等)でも落ちないようガードする。
 */
function getStoredLocale(): Locale | null {
  try {
    const stored = window.localStorage.getItem(LOCALE_STORAGE_KEY);
    return stored !== null && stored in messagesByLocale ? (stored as Locale) : null;
  } catch {
    return null;
  }
}

/**
 * サーバー側(SSR)およびハイドレーション時のスナップショット。
 * 常にnullを返すことで、初回HTMLとハイドレーション直後のレンダー結果が一致し、
 * ハイドレーション不一致が起きない(その後クライアントのスナップショットへ切り替わる)。
 */
function getServerLocaleSnapshot(): Locale | null {
  return null;
}

interface I18nProviderProps {
  children: ReactNode;
  initialLocale?: Locale;
}

/**
 * localStorageに保存された言語設定を反映する。
 *
 * <p>issue #721: 以前は`useEffect`内で`localStorage`を読んで`setLocale`を同期的に呼んでおり、
 * `react-hooks/set-state-in-effect`のlintエラーになっていた。単純に`useState`の遅延初期化へ
 * 移すとSSR(localStorageが無くinitialLocale)とクライアント(保存値)で初回レンダー結果が
 * ずれてハイドレーション不一致になるため、外部ストアの購読に適した`useSyncExternalStore`を使う。
 * サーバー用スナップショットが常にnullなので初回HTMLとハイドレーションは一致し、その直後に
 * クライアントのスナップショットへ切り替わる。表示される内容は変更前と同じ。
 */
export function I18nProvider({ children, initialLocale = "ja" }: I18nProviderProps) {
  const storedLocale = useSyncExternalStore(
    subscribeToStoredLocale,
    getStoredLocale,
    getServerLocaleSnapshot
  );
  const locale = storedLocale ?? initialLocale;

  const messages = messagesByLocale[locale];

  const t = (namespace: keyof Messages, key: string): string => {
    if (!messages) return key;
    const namespaceMessages = messages[namespace];
    if (!namespaceMessages) return key;
    return (namespaceMessages as Record<string, string>)[key] || key;
  };

  return (
    <I18nContext.Provider value={{ locale, messages, t }}>
      {children}
    </I18nContext.Provider>
  );
}

export function useI18n() {
  const context = useContext(I18nContext);
  if (!context) {
    throw new Error("useI18n must be used within I18nProvider");
  }
  return context;
}
