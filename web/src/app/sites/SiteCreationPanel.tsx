"use client";

import { useState } from "react";
import type { AppUser } from "@/lib/apiClient";
import { SiteForm } from "./SiteForm";
import { ManagedWordPressForm } from "./ManagedWordPressForm";

type Mode = "external" | "managed-wordpress";

export function SiteCreationPanel({ users }: { users: AppUser[] }) {
  const [mode, setMode] = useState<Mode>("external");

  return (
    <div className="space-y-3">
      <div className="flex gap-2 text-sm">
        <button
          type="button"
          onClick={() => setMode("external")}
          className={`rounded px-3 py-1.5 ${
            mode === "external" ? "bg-neutral-900 text-white" : "bg-neutral-100 text-neutral-600"
          }`}
        >
          既存サイトを登録
        </button>
        <button
          type="button"
          onClick={() => setMode("managed-wordpress")}
          className={`rounded px-3 py-1.5 ${
            mode === "managed-wordpress" ? "bg-neutral-900 text-white" : "bg-neutral-100 text-neutral-600"
          }`}
        >
          WordPressを新規構築
        </button>
      </div>
      {mode === "external" ? <SiteForm /> : <ManagedWordPressForm users={users} />}
    </div>
  );
}
