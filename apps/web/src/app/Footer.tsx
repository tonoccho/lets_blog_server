"use client";

import { useI18n } from "./I18nProvider";

export function Footer() {
  const { t } = useI18n();

  return (
    <footer className="border-t border-neutral-200 bg-white px-4 py-4 text-center text-sm text-neutral-600 dark:border-neutral-800 dark:bg-neutral-900 dark:text-neutral-400">
      {t("footer", "copyright")}
    </footer>
  );
}
