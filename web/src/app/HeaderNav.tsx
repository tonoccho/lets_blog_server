"use client";

import { useState } from "react";
import Link from "next/link";
import { Menu, X } from "lucide-react";
import type { NavItem } from "@/lib/navigation";

export function HeaderNav({ navItems }: { navItems: NavItem[] }) {
  const [isOpen, setIsOpen] = useState(false);

  return (
    <>
      {/* Desktop navigation */}
      <nav className="hidden min-w-0 flex-1 items-center gap-1 text-sm sm:flex">
        {navItems.map((item) => {
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
            {navItems.map((item) => {
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
          </nav>
        </div>
      )}
    </>
  );
}
