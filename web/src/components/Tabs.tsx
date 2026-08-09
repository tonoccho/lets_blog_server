"use client";

import { useState } from "react";

export type TabItem = {
  id: string;
  label: string;
  content: React.ReactNode;
};

export function Tabs({ tabs }: { tabs: TabItem[] }) {
  const [activeTabId, setActiveTabId] = useState(tabs[0]?.id || "");

  const activeTab = tabs.find((tab) => tab.id === activeTabId);

  return (
    <div className="space-y-4">
      {/* Tab buttons */}
      <div className="flex gap-2 border-b border-neutral-200 dark:border-neutral-800 overflow-x-auto">
        {tabs.map((tab) => (
          <button
            key={tab.id}
            onClick={() => setActiveTabId(tab.id)}
            className={`px-4 py-3 text-sm font-medium whitespace-nowrap border-b-2 transition-colors ${
              activeTabId === tab.id
                ? "border-neutral-900 dark:border-neutral-50 text-neutral-900 dark:text-neutral-50"
                : "border-transparent text-neutral-600 dark:text-neutral-400 hover:text-neutral-900 dark:hover:text-neutral-50"
            }`}
          >
            {tab.label}
          </button>
        ))}
      </div>

      {/* Tab content */}
      <div>{activeTab?.content}</div>
    </div>
  );
}
