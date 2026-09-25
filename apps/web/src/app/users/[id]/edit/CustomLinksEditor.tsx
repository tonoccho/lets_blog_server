"use client";

import { useState } from "react";
import type { CustomLink } from "@/lib/apiClient";

export function CustomLinksEditor({ defaultLinks }: { defaultLinks: CustomLink[] | null }) {
  const [links, setLinks] = useState<CustomLink[]>(defaultLinks ?? []);

  const handleAddLink = () => {
    setLinks([...links, { label: "", url: "" }]);
  };

  const handleRemoveLink = (index: number) => {
    setLinks(links.filter((_, i) => i !== index));
  };

  const handleLinkChange = (index: number, field: "label" | "url", value: string) => {
    const newLinks = [...links];
    newLinks[index] = { ...newLinks[index], [field]: value };
    setLinks(newLinks);
  };

  return (
    <fieldset className="flex flex-col gap-2">
      <legend className="text-sm font-medium text-neutral-600 dark:text-neutral-400">カスタムリンク</legend>

      <div className="space-y-3">
        {links.map((link, index) => (
          <div key={index} className="flex gap-2">
            <input
              type="text"
              placeholder="ラベル(例: 自分のブログ)"
              value={link.label}
              onChange={(e) => handleLinkChange(index, "label", e.target.value)}
              className="flex-1 rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm"
            />
            <input
              type="url"
              placeholder="https://example.com"
              value={link.url}
              onChange={(e) => handleLinkChange(index, "url", e.target.value)}
              className="flex-1 rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm"
            />
            <button
              type="button"
              onClick={() => handleRemoveLink(index)}
              className="rounded bg-red-100 px-3 py-2 text-sm text-red-700 hover:bg-red-200"
            >
              削除
            </button>
          </div>
        ))}
      </div>

      <button
        type="button"
        onClick={handleAddLink}
        className="self-start rounded bg-neutral-200 dark:bg-neutral-700 px-4 py-2 text-sm text-neutral-700 dark:text-neutral-300 hover:bg-neutral-300"
      >
        + 追加
      </button>

      <input type="hidden" name="customLinks" value={JSON.stringify(links)} />
    </fieldset>
  );
}
