"use client";

import { useEffect, useState } from "react";
import { Moon, Sun } from "lucide-react";

type Theme = "light" | "dark" | "auto";

const applyTheme = (newTheme: Theme) => {
  const html = document.documentElement;
  if (newTheme === "auto") {
    html.removeAttribute("data-theme");
    localStorage.removeItem("theme");
  } else {
    html.setAttribute("data-theme", newTheme);
    localStorage.setItem("theme", newTheme);
  }
};

export function ThemeSwitcher() {
  const [theme, setTheme] = useState<Theme>("auto");
  const [mounted, setMounted] = useState(false);

  useEffect(() => {
    setMounted(true);
    const stored = localStorage.getItem("theme") as Theme | null;
    if (stored) {
      setTheme(stored);
      applyTheme(stored);
    } else {
      applyTheme("auto");
    }
  }, []);

  const nextTheme = (): Theme => {
    const themes: Theme[] = ["light", "dark", "auto"];
    const currentIndex = themes.indexOf(theme);
    return themes[(currentIndex + 1) % themes.length];
  };

  const handleToggle = () => {
    const next = nextTheme();
    setTheme(next);
    applyTheme(next);
  };

  if (!mounted) return null;

  return (
    <button
      onClick={handleToggle}
      className="rounded-md p-2 hover:bg-neutral-100 dark:hover:bg-neutral-800 transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-500"
      title={`Theme: ${theme} (Click to switch)`}
      aria-label={`Switch theme. Current: ${theme}`}
    >
      {theme === "dark" || (theme === "auto" && window.matchMedia("(prefers-color-scheme: dark)").matches) ? (
        <Moon className="h-5 w-5" />
      ) : (
        <Sun className="h-5 w-5" />
      )}
    </button>
  );
}
