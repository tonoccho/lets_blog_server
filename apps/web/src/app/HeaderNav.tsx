"use client";

import { useRef, useState, useEffect } from "react";
import Link from "next/link";
import { Menu, X, ChevronDown } from "lucide-react";
import { useI18n } from "./I18nProvider";
import type { NavItem } from "@/lib/navigation";
import { ICON_MAP } from "@/lib/navigation";

export function HeaderNav({ navItems }: { navItems: NavItem[] }) {
  const { t } = useI18n();
  const labelOf = (item: NavItem) => t("nav", item.labelKey);
  const [isOpen, setIsOpen] = useState(false);
  const [isAdminDropdownOpen, setIsAdminDropdownOpen] = useState(false);
  const adminDropdownRef = useRef<HTMLDivElement>(null);

  const regularItems = navItems.filter((item) => item.group !== "admin");
  const adminItems = navItems.filter((item) => item.group === "admin");

  useEffect(() => {
    function handleClickOutside(event: MouseEvent) {
      if (
        adminDropdownRef.current &&
        !adminDropdownRef.current.contains(event.target as Node)
      ) {
        setIsAdminDropdownOpen(false);
      }
    }

    if (isAdminDropdownOpen) {
      document.addEventListener("mousedown", handleClickOutside);
    }

    return () => {
      document.removeEventListener("mousedown", handleClickOutside);
    };
  }, [isAdminDropdownOpen]);

  useEffect(() => {
    function handleEscape(e: KeyboardEvent) {
      if (e.key === "Escape" && isOpen) {
        setIsOpen(false);
      }
    }

    document.addEventListener("keydown", handleEscape);
    return () => document.removeEventListener("keydown", handleEscape);
  }, [isOpen]);

  useEffect(() => {
    function handleEscape(e: KeyboardEvent) {
      if (e.key === "Escape" && isOpen) {
        setIsOpen(false);
      }
    }

    document.addEventListener("keydown", handleEscape);
    return () => document.removeEventListener("keydown", handleEscape);
  }, [isOpen]);

  return (
    <div className="relative flex min-w-0 flex-1 items-center gap-1">
      {/* Desktop navigation */}
      <nav className="hidden min-w-0 flex-1 items-center gap-1 text-sm lg:flex">
        {regularItems.map((item) => {
          const Icon = ICON_MAP[item.icon];
          return (
            <Link
              key={item.href}
              href={item.href}
              title={labelOf(item)}
              className="flex shrink-0 items-center gap-2 whitespace-nowrap rounded-lg px-3 py-2 text-neutral-600 dark:text-neutral-400 transition-colors hover:bg-neutral-100 dark:hover:bg-neutral-800 hover:text-neutral-900 dark:hover:text-neutral-50 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-500"
            >
              <Icon className="h-4 w-4" aria-hidden="true" />
              <span className="font-medium">{labelOf(item)}</span>
            </Link>
          );
        })}

        {adminItems.length > 0 && (
          <div className="relative" ref={adminDropdownRef}>
            <button
              onClick={() => setIsAdminDropdownOpen(!isAdminDropdownOpen)}
              aria-label={t("header", "openAdminMenu")}
              aria-expanded={isAdminDropdownOpen}
              className="flex shrink-0 items-center gap-1 whitespace-nowrap rounded-lg px-3 py-2 text-neutral-600 dark:text-neutral-400 transition-colors hover:bg-neutral-100 dark:hover:bg-neutral-800 hover:text-neutral-900 dark:hover:text-neutral-50"
            >
              <span className="font-medium" data-testid="header-admin-menu-label">
                {t("header", "admin")}
              </span>
              <ChevronDown
                className={`h-4 w-4 transition-transform ${
                  isAdminDropdownOpen ? "rotate-180" : ""
                }`}
                aria-hidden="true"
              />
            </button>

            {isAdminDropdownOpen && (
              <div className="absolute top-full right-0 z-50 mt-1 rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 shadow-lg">
                {adminItems.map((item) => {
                  const Icon = ICON_MAP[item.icon];
                  return (
                    <Link
                      key={item.href}
                      href={item.href}
                      onClick={() => setIsAdminDropdownOpen(false)}
                      className="flex items-center gap-2 whitespace-nowrap px-4 py-2 text-neutral-600 dark:text-neutral-400 transition-colors hover:bg-neutral-100 dark:hover:bg-neutral-800 hover:text-neutral-900 dark:hover:text-neutral-50 first:rounded-t-lg last:rounded-b-lg"
                    >
                      <Icon className="h-4 w-4" aria-hidden="true" />
                      <span className="font-medium">{labelOf(item)}</span>
                    </Link>
                  );
                })}
              </div>
            )}
          </div>
        )}
      </nav>

      {/* Mobile hamburger button */}
      <button
        onClick={() => setIsOpen(!isOpen)}
        aria-label={isOpen ? t("header", "closeMenu") : t("header", "openMenu")}
        aria-expanded={isOpen}
        className="ml-1 flex items-center lg:hidden focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-500 rounded"
      >
        {isOpen ? (
          <X className="h-5 w-5 text-neutral-600 dark:text-neutral-400" />
        ) : (
          <Menu className="h-5 w-5 text-neutral-600 dark:text-neutral-400" />
        )}
      </button>

      {/* Mobile drawer menu */}
      {isOpen && (
        <div className="absolute left-0 right-0 top-full z-50 border-b border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 lg:hidden" role="dialog" aria-label={t("header", "navigation")}>
          <nav className="space-y-1 px-4 py-3 text-sm">
            {regularItems.map((item) => {
              const Icon = ICON_MAP[item.icon];
              return (
                <Link
                  key={item.href}
                  href={item.href}
                  onClick={() => setIsOpen(false)}
                  className="flex items-center gap-2 rounded-lg px-3 py-2 text-neutral-600 dark:text-neutral-400 transition-colors hover:bg-neutral-100 dark:hover:bg-neutral-800 hover:text-neutral-900 dark:hover:text-neutral-50 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-500"
                >
                  <Icon className="h-4 w-4" aria-hidden="true" />
                  <span className="font-medium">{labelOf(item)}</span>
                </Link>
              );
            })}

            {adminItems.length > 0 && (
              <>
                <div className="border-t border-neutral-200 dark:border-neutral-800 my-2" />
                <div className="font-medium text-neutral-500 dark:text-neutral-400 px-3 py-2 text-xs uppercase tracking-wide">
                  {t("header", "admin")}
                </div>
                {adminItems.map((item) => {
                  const Icon = ICON_MAP[item.icon];
                  return (
                    <Link
                      key={item.href}
                      href={item.href}
                      onClick={() => setIsOpen(false)}
                      className="flex items-center gap-2 rounded-lg px-3 py-2 text-neutral-600 dark:text-neutral-400 transition-colors hover:bg-neutral-100 dark:hover:bg-neutral-800 hover:text-neutral-900 dark:hover:text-neutral-50 ml-2"
                    >
                      <Icon className="h-4 w-4" aria-hidden="true" />
                      <span className="font-medium">{labelOf(item)}</span>
                    </Link>
                  );
                })}
              </>
            )}
          </nav>
        </div>
      )}
    </div>
  );
}
