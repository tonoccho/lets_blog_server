"use client";

import { signOut } from "next-auth/react";
import { useI18n } from "./I18nProvider";

export function LogoutButton() {
  const { t } = useI18n();

  return (
    <button
      type="button"
      onClick={() => signOut({ callbackUrl: "/login" })}
      aria-label={t("header", "logout")}
      className="text-sm text-neutral-900 dark:text-neutral-50 hover:text-neutral-700 dark:hover:text-neutral-300 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-500 rounded px-2 py-1"
    >
      {t("header", "logout")}
    </button>
  );
}
