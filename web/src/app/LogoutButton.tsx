"use client";

import { signOut } from "next-auth/react";

export function LogoutButton() {
  return (
    <button
      type="button"
      onClick={() => signOut({ callbackUrl: "/login" })}
      className="text-sm text-neutral-600 hover:text-neutral-900"
    >
      ログアウト
    </button>
  );
}
