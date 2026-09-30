"use client";

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useState,
  useSyncExternalStore,
  type ReactNode,
} from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { Menu, X, PanelLeftClose, PanelLeftOpen } from "lucide-react";
import { useI18n } from "./I18nProvider";
import type { NavItem } from "@/lib/navigation";
import { ICON_MAP } from "@/lib/navigation";

const COLLAPSED_STORAGE_KEY = "sideNavCollapsed";

// 折りたたみ状態はlocalStorageに置き、ページ遷移後も保つ。同一タブ内の更新は購読者へ通知する。
const collapsedListeners = new Set<() => void>();

function subscribeCollapsed(listener: () => void): () => void {
  collapsedListeners.add(listener);
  return () => {
    collapsedListeners.delete(listener);
  };
}

// localStorageが読み書きできない環境(プライベートブラウジング等)でも、その場の操作は反映する。
let memoryCollapsed = false;

function getCollapsedSnapshot(): boolean {
  try {
    const stored = window.localStorage.getItem(COLLAPSED_STORAGE_KEY);
    return stored === null ? memoryCollapsed : stored === "true";
  } catch {
    return memoryCollapsed;
  }
}

// サーバー描画とハイドレーションでは常に展開状態にし、初回HTMLとの不一致を避ける。
function getCollapsedServerSnapshot(): boolean {
  return false;
}

type SideNavContextType = {
  collapsed: boolean;
  setCollapsed: (next: boolean) => void;
  drawerOpen: boolean;
  setDrawerOpen: (next: boolean) => void;
};

const SideNavContext = createContext<SideNavContextType | undefined>(undefined);

function useSideNav(): SideNavContextType {
  const context = useContext(SideNavContext);
  if (!context) {
    throw new Error("SideNav components must be used within SideNavProvider");
  }
  return context;
}

export function SideNavProvider({ children }: { children: ReactNode }) {
  const collapsed = useSyncExternalStore(
    subscribeCollapsed,
    getCollapsedSnapshot,
    getCollapsedServerSnapshot,
  );
  const [drawerOpen, setDrawerOpen] = useState(false);

  const setCollapsed = useCallback((next: boolean) => {
    memoryCollapsed = next;
    try {
      window.localStorage.setItem(COLLAPSED_STORAGE_KEY, String(next));
    } catch {
      // 保存できなくても、メモリ上の状態でこのセッション内の表示は切り替わる。
    }
    collapsedListeners.forEach((listener) => listener());
  }, []);

  return (
    <SideNavContext.Provider value={{ collapsed, setCollapsed, drawerOpen, setDrawerOpen }}>
      {children}
    </SideNavContext.Provider>
  );
}

const FOCUS_RING =
  "focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-500";

/** 狭い画面でドロワーを開くボタン。ヘッダーに置く。開いたドロワーはこれを覆うため、閉じる操作はドロワー内にある。 */
export function SideNavToggle() {
  const { t } = useI18n();
  const { drawerOpen, setDrawerOpen } = useSideNav();
  return (
    <button
      type="button"
      onClick={() => setDrawerOpen(true)}
      aria-label={t("sideNav", "openMenu")}
      aria-expanded={drawerOpen}
      className={`flex shrink-0 items-center rounded lg:hidden ${FOCUS_RING}`}
    >
      <Menu className="h-5 w-5 text-neutral-600 dark:text-neutral-400" />
    </button>
  );
}

function isCurrent(pathname: string | null, href: string): boolean {
  if (pathname === null) return false;
  return href === "/" ? pathname === "/" : pathname === href || pathname.startsWith(`${href}/`);
}

function NavList({
  navItems,
  collapsed,
  onNavigate,
  desktop,
}: {
  navItems: NavItem[];
  collapsed: boolean;
  onNavigate?: () => void;
  desktop: boolean;
}) {
  const { t } = useI18n();
  const pathname = usePathname();
  const regularItems = navItems.filter((item) => item.group !== "admin");
  const adminItems = navItems.filter((item) => item.group === "admin");

  const renderLink = (item: NavItem) => {
    const Icon = ICON_MAP[item.icon];
    const label = t("nav", item.labelKey);
    const current = isCurrent(pathname, item.href);
    return (
      <Link
        key={item.href}
        href={item.href}
        title={label}
        aria-label={label}
        aria-current={current ? "page" : undefined}
        onClick={onNavigate}
        className={`flex items-center gap-2 whitespace-nowrap rounded-lg px-3 py-2 transition-colors ${FOCUS_RING} ${
          collapsed ? "justify-center" : ""
        } ${
          current
            ? "bg-neutral-100 text-neutral-900 dark:bg-neutral-800 dark:text-neutral-50"
            : "text-neutral-600 hover:bg-neutral-100 hover:text-neutral-900 dark:text-neutral-400 dark:hover:bg-neutral-800 dark:hover:text-neutral-50"
        }`}
      >
        <Icon className="h-4 w-4 shrink-0" aria-hidden="true" />
        {!collapsed && <span className="font-medium">{label}</span>}
      </Link>
    );
  };

  return (
    <nav className="space-y-1 text-sm">
      {regularItems.map(renderLink)}
      {adminItems.length > 0 && (
        <>
          <div className="my-2 border-t border-neutral-200 dark:border-neutral-800" />
          {!collapsed && (
            <div
              data-testid={desktop ? "side-nav-admin-label" : undefined}
              className="px-3 py-2 text-xs font-medium uppercase tracking-wide text-neutral-500 dark:text-neutral-400"
            >
              {t("sideNav", "admin")}
            </div>
          )}
          {adminItems.map(renderLink)}
        </>
      )}
    </nav>
  );
}

/** 本文の左に常設するナビゲーション。狭い画面ではドロワーとして開閉する。 */
export function SideNav({ navItems }: { navItems: NavItem[] }) {
  const { t } = useI18n();
  const { collapsed, setCollapsed, drawerOpen, setDrawerOpen } = useSideNav();

  useEffect(() => {
    if (!drawerOpen) return;
    function handleEscape(e: KeyboardEvent) {
      if (e.key === "Escape") setDrawerOpen(false);
    }
    document.addEventListener("keydown", handleEscape);
    return () => document.removeEventListener("keydown", handleEscape);
  }, [drawerOpen, setDrawerOpen]);

  return (
    <>
      <aside
        data-testid="side-nav"
        data-collapsed={collapsed}
        className={`hidden shrink-0 flex-col border-r border-neutral-200 bg-white p-2 transition-[width] dark:border-neutral-800 dark:bg-neutral-900 lg:flex ${
          collapsed ? "w-16" : "w-64"
        }`}
      >
        <div className="flex-1">
          <NavList navItems={navItems} collapsed={collapsed} desktop />
        </div>
        <button
          type="button"
          onClick={() => setCollapsed(!collapsed)}
          aria-label={collapsed ? t("sideNav", "expand") : t("sideNav", "collapse")}
          title={collapsed ? t("sideNav", "expand") : t("sideNav", "collapse")}
          className={`mt-2 flex items-center rounded-lg px-3 py-2 text-neutral-600 hover:bg-neutral-100 dark:text-neutral-400 dark:hover:bg-neutral-800 ${FOCUS_RING} ${
            collapsed ? "justify-center" : "justify-end"
          }`}
        >
          {collapsed ? (
            <PanelLeftOpen className="h-4 w-4" aria-hidden="true" />
          ) : (
            <PanelLeftClose className="h-4 w-4" aria-hidden="true" />
          )}
        </button>
      </aside>

      {drawerOpen && (
        <div className="fixed inset-0 z-50 lg:hidden">
          <div
            data-testid="side-nav-backdrop"
            className="absolute inset-0 bg-black/40"
            onClick={() => setDrawerOpen(false)}
          />
          <div
            role="dialog"
            aria-label={t("sideNav", "navigation")}
            className="absolute inset-y-0 left-0 w-64 max-w-[85vw] overflow-y-auto bg-white p-2 dark:bg-neutral-900"
          >
            <button
              type="button"
              onClick={() => setDrawerOpen(false)}
              aria-label={t("sideNav", "closeMenu")}
              className={`mb-2 ml-auto flex items-center rounded p-1 ${FOCUS_RING}`}
            >
              <X className="h-5 w-5 text-neutral-600 dark:text-neutral-400" aria-hidden="true" />
            </button>
            <NavList
              navItems={navItems}
              collapsed={false}
              desktop={false}
              onNavigate={() => setDrawerOpen(false)}
            />
          </div>
        </div>
      )}
    </>
  );
}
