"use client";

import { useState, useRef, useEffect } from "react";
import Link from "next/link";
import { Menu, X, ChevronDown } from "lucide-react";
import type { NavItem } from "@/lib/navigation";

export function HeaderNav({ navItems }: { navItems: NavItem[] }) {
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

  return (
    <>
      {/* Desktop navigation */}
      <nav className="hidden min-w-0 flex-1 items-center gap-1 text-sm sm:flex">
        {regularItems.map((item) => {
          const Icon = item.icon;
          return (
            <Link
              key={item.href}
              href={item.href}
              title={item.label}
              className="flex shrink-0 items-center gap-2 whitespace-nowrap rounded-lg px-3 py-2 text-neutral-600 transition-colors hover:bg-neutral-100 hover:text-neutral-900"
            >
              <Icon className="h-4 w-4" aria-hidden="true" />
              <span className="font-medium">{item.label}</span>
            </Link>
          );
        })}

        {adminItems.length > 0 && (
          <div className="relative" ref={adminDropdownRef}>
            <button
              onClick={() => setIsAdminDropdownOpen(!isAdminDropdownOpen)}
              aria-label="管理メニューを開く"
              aria-expanded={isAdminDropdownOpen}
              className="flex shrink-0 items-center gap-1 whitespace-nowrap rounded-lg px-3 py-2 text-neutral-600 transition-colors hover:bg-neutral-100 hover:text-neutral-900"
            >
              <span className="font-medium">管理</span>
              <ChevronDown
                className={`h-4 w-4 transition-transform ${
                  isAdminDropdownOpen ? "rotate-180" : ""
                }`}
                aria-hidden="true"
              />
            </button>

            {isAdminDropdownOpen && (
              <div className="absolute top-full right-0 z-50 mt-1 rounded-lg border border-neutral-200 bg-white shadow-lg">
                {adminItems.map((item) => {
                  const Icon = item.icon;
                  return (
                    <Link
                      key={item.href}
                      href={item.href}
                      onClick={() => setIsAdminDropdownOpen(false)}
                      className="flex items-center gap-2 whitespace-nowrap px-4 py-2 text-neutral-600 transition-colors hover:bg-neutral-100 hover:text-neutral-900 first:rounded-t-lg last:rounded-b-lg"
                    >
                      <Icon className="h-4 w-4" aria-hidden="true" />
                      <span className="font-medium">{item.label}</span>
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
        aria-label={isOpen ? "メニューを閉じる" : "メニューを開く"}
        aria-expanded={isOpen}
        className="ml-1 flex items-center sm:hidden"
      >
        {isOpen ? (
          <X className="h-5 w-5 text-neutral-600" />
        ) : (
          <Menu className="h-5 w-5 text-neutral-600" />
        )}
      </button>

      {/* Mobile drawer menu */}
      {isOpen && (
        <div className="absolute left-0 right-0 top-full z-50 border-b border-neutral-200 bg-white sm:hidden">
          <nav className="space-y-1 px-4 py-3 text-sm">
            {regularItems.map((item) => {
              const Icon = item.icon;
              return (
                <Link
                  key={item.href}
                  href={item.href}
                  onClick={() => setIsOpen(false)}
                  className="flex items-center gap-2 rounded-lg px-3 py-2 text-neutral-600 transition-colors hover:bg-neutral-100 hover:text-neutral-900"
                >
                  <Icon className="h-4 w-4" aria-hidden="true" />
                  <span className="font-medium">{item.label}</span>
                </Link>
              );
            })}

            {adminItems.length > 0 && (
              <>
                <div className="border-t border-neutral-200 my-2" />
                <div className="font-medium text-neutral-500 px-3 py-2 text-xs uppercase tracking-wide">
                  管理
                </div>
                {adminItems.map((item) => {
                  const Icon = item.icon;
                  return (
                    <Link
                      key={item.href}
                      href={item.href}
                      onClick={() => setIsOpen(false)}
                      className="flex items-center gap-2 rounded-lg px-3 py-2 text-neutral-600 transition-colors hover:bg-neutral-100 hover:text-neutral-900 ml-2"
                    >
                      <Icon className="h-4 w-4" aria-hidden="true" />
                      <span className="font-medium">{item.label}</span>
                    </Link>
                  );
                })}
              </>
            )}
          </nav>
        </div>
      )}
    </>
  );
}
