"use client";

import { useCallback, useEffect, useState, useSyncExternalStore } from "react";
import { PanelRightClose, PanelRightOpen, X } from "lucide-react";
import { Tabs } from "@/components/Tabs";
import { useI18n } from "./I18nProvider";
import { InfoRailOperationLogs } from "./InfoRailOperationLogs";
import { InfoRailQueue } from "./InfoRailQueue";

const COLLAPSED_KEY = "infoRailCollapsed";
const TAB_KEY = "infoRailTab";
const TAB_IDS = ["queue", "logs"] as const;
type TabId = (typeof TAB_IDS)[number];

const FOCUS_RING =
  "focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-500";

/**
 * localStorage に置く値を購読可能にする。ページ遷移・再読み込み後も保たれる。
 * localStorage が使えない環境では、その場の操作だけメモリ上で反映する。
 * サーバー描画とハイドレーションでは既定値を返し、初回HTMLとの不一致を避ける。
 */
function createPersistedValue(key: string, fallback: string) {
  const listeners = new Set<() => void>();
  let memory = fallback;
  return {
    subscribe(listener: () => void) {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
    get(): string {
      try {
        return window.localStorage.getItem(key) ?? fallback;
      } catch {
        return memory;
      }
    },
    getServer(): string {
      return fallback;
    },
    set(next: string) {
      memory = next;
      try {
        window.localStorage.setItem(key, next);
      } catch {
        // 保存できなくても、メモリ上の状態でこのセッション内の表示は切り替わる。
      }
      listeners.forEach((listener) => listener());
    },
  };
}

const collapsedStore = createPersistedValue(COLLAPSED_KEY, "false");
const tabStore = createPersistedValue(TAB_KEY, "queue");

/**
 * 本文の右に常設する情報表示レール。タブで「処理キュー」と「操作ログ」を切り替える。
 * 広い画面では折りたたみ可能な列、狭い画面では既定で閉じ、開くとドロワーとして本文に重なる。
 */
export function InfoRail() {
  const { t } = useI18n();
  const collapsed =
    useSyncExternalStore(collapsedStore.subscribe, collapsedStore.get, collapsedStore.getServer) === "true";
  const storedTab = useSyncExternalStore(tabStore.subscribe, tabStore.get, tabStore.getServer);
  const activeTab: TabId = (TAB_IDS as readonly string[]).includes(storedTab) ? (storedTab as TabId) : "queue";
  const [drawerOpen, setDrawerOpen] = useState(false);

  const closeDrawer = useCallback(() => setDrawerOpen(false), []);

  useEffect(() => {
    if (!drawerOpen) return;
    function handleEscape(e: KeyboardEvent) {
      if (e.key === "Escape") closeDrawer();
    }
    document.addEventListener("keydown", handleEscape);
    return () => document.removeEventListener("keydown", handleEscape);
  }, [drawerOpen, closeDrawer]);

  const showContent = drawerOpen || !collapsed;
  const collapseLabel = collapsed ? t("infoRail", "expand") : t("infoRail", "collapse");

  return (
    <>
      {!drawerOpen && (
        <button
          type="button"
          onClick={() => setDrawerOpen(true)}
          aria-label={t("infoRail", "open")}
          className={`fixed right-0 top-1/2 z-30 rounded-l border border-r-0 border-neutral-200 bg-white p-2 dark:border-neutral-800 dark:bg-neutral-900 lg:hidden ${FOCUS_RING}`}
        >
          <PanelRightOpen className="h-4 w-4 text-neutral-600 dark:text-neutral-400" aria-hidden="true" />
        </button>
      )}
      {drawerOpen && (
        <div
          data-testid="info-rail-backdrop"
          className="fixed inset-0 z-40 bg-black/40 lg:hidden"
          onClick={closeDrawer}
        />
      )}
      <aside
        data-testid="info-rail"
        data-collapsed={collapsed}
        role={drawerOpen ? "dialog" : undefined}
        aria-label={t("infoRail", "title")}
        className={`min-w-0 flex-col overflow-y-auto border-l border-neutral-200 bg-white p-2 dark:border-neutral-800 dark:bg-neutral-900 lg:static lg:z-auto lg:flex lg:shrink-0 lg:max-w-none ${
          drawerOpen ? "fixed inset-y-0 right-0 z-50 flex w-80 max-w-[85vw]" : "hidden"
        } ${collapsed ? "lg:w-12" : "lg:w-72"}`}
      >
        <button
          type="button"
          onClick={closeDrawer}
          aria-label={t("infoRail", "close")}
          className={`mb-2 ml-auto flex items-center rounded p-1 lg:hidden ${FOCUS_RING}`}
        >
          <X className="h-5 w-5 text-neutral-600 dark:text-neutral-400" aria-hidden="true" />
        </button>
        <button
          type="button"
          onClick={() => collapsedStore.set(String(!collapsed))}
          aria-label={collapseLabel}
          title={collapseLabel}
          className={`mb-2 hidden items-center rounded-lg px-3 py-2 text-neutral-600 hover:bg-neutral-100 dark:text-neutral-400 dark:hover:bg-neutral-800 lg:flex ${FOCUS_RING} ${
            collapsed ? "justify-center" : "justify-start"
          }`}
        >
          {collapsed ? (
            <PanelRightOpen className="h-4 w-4" aria-hidden="true" />
          ) : (
            <PanelRightClose className="h-4 w-4" aria-hidden="true" />
          )}
        </button>
        {showContent && (
          <Tabs
            activeTabId={activeTab}
            onTabChange={(id) => tabStore.set(id)}
            tabs={[
              {
                id: "queue",
                label: t("infoRail", "queueTab"),
                content: <InfoRailQueue />,
              },
              { id: "logs", label: t("infoRail", "logsTab"), content: <InfoRailOperationLogs /> },
            ]}
          />
        )}
      </aside>
    </>
  );
}
