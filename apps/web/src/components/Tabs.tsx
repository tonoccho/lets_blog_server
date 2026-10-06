"use client";

import { useState } from "react";

export type TabItem = {
  id: string;
  label: string;
  content: React.ReactNode;
};

/**
 * `activeTabId` を渡すと選択状態を呼び出し側が持つ(制御モード。永続化したいとき用)。
 * 渡さなければ従来どおり内部状態で切り替わる。どちらでも `onTabChange` は選択時に呼ばれる。
 */
export function Tabs({
  tabs,
  activeTabId: controlledTabId,
  onTabChange,
  defaultTabId,
}: {
  tabs: TabItem[];
  /** 非制御モードで最初に選択するタブ。存在しない id なら先頭タブ。 */
  defaultTabId?: string;
  activeTabId?: string;
  onTabChange?: (id: string) => void;
}) {
  const [internalTabId, setInternalTabId] = useState(
    tabs.some((tab) => tab.id === defaultTabId) ? (defaultTabId as string) : tabs[0]?.id || "",
  );
  // 同じ画面のままURLの `?tab=` だけが変わる遷移(例: 処理キューの「結果を見る」)ではコンポーネントが
  // 作り直されないので、defaultTabId の変化を選択中のタブへ反映する(render 中の state 調整)。
  const [seenDefaultTabId, setSeenDefaultTabId] = useState(defaultTabId);
  if (seenDefaultTabId !== defaultTabId) {
    setSeenDefaultTabId(defaultTabId);
    if (tabs.some((tab) => tab.id === defaultTabId)) setInternalTabId(defaultTabId as string);
  }
  const activeTabId = controlledTabId ?? internalTabId;

  function selectTab(id: string) {
    if (controlledTabId === undefined) setInternalTabId(id);
    onTabChange?.(id);
  }

  const activeTab = tabs.find((tab) => tab.id === activeTabId);

  return (
    <div className="space-y-4">
      {/* Tab buttons */}
      <div className="flex gap-2 border-b border-neutral-200 dark:border-neutral-800 overflow-x-auto">
        {tabs.map((tab) => (
          <button
            key={tab.id}
            onClick={() => selectTab(tab.id)}
            aria-pressed={activeTabId === tab.id}
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
