"use client";

import { useEffect, useState } from "react";
import type { Locale } from "@/lib/i18nConfig";

export function LanguageSwitcher() {
  const [locale, setLocaleState] = useState<Locale>("ja");
  const [mounted, setMounted] = useState(false);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setMounted(true);
    const saved = localStorage.getItem("locale") as Locale | null;
    if (saved) {
      setLocaleState(saved);
    }
  }, []);

  const handleLanguageChange = (newLocale: string) => {
    const locale = newLocale as Locale;
    setLocaleState(locale);
    localStorage.setItem("locale", locale);
    window.location.reload();
  };

  if (!mounted) return null;

  return (
    <select
      value={locale}
      onChange={(e) => handleLanguageChange(e.target.value)}
      aria-label="言語選択"
      className="text-sm border border-neutral-200 rounded px-2 py-1 bg-white dark:bg-neutral-900 dark:border-neutral-700 text-neutral-900 dark:text-neutral-50"
    >
      <option value="ja">日本語</option>
      <option value="en">English</option>
    </select>
  );
}
