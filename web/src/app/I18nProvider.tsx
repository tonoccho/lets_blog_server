"use client";

import { createContext, useContext, ReactNode, useState, useEffect } from "react";
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

interface I18nProviderProps {
  children: ReactNode;
  initialLocale?: Locale;
}

export function I18nProvider({ children, initialLocale = "ja" }: I18nProviderProps) {
  const [locale, setLocale] = useState<Locale>(initialLocale);

  useEffect(() => {
    const storedLocale = localStorage.getItem("locale") as Locale | null;
    if (storedLocale && storedLocale in messagesByLocale) {
      setLocale(storedLocale);
    }
  }, []);

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
