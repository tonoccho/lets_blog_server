"use client";

import { useEffect, useState } from "react";
import { Moon, Sun } from "lucide-react";

type Theme = "light" | "dark" | "auto";

const resolveEffectiveTheme = (theme: Theme): "light" | "dark" =>
  theme === "auto"
    ? (window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light")
    : theme;

const applyTheme = (newTheme: Theme) => {
  const html = document.documentElement;
  html.setAttribute("data-theme", resolveEffectiveTheme(newTheme));
  if (newTheme === "auto") {
    localStorage.removeItem("theme");
  } else {
    localStorage.setItem("theme", newTheme);
  }
};

export function ThemeSwitcher() {
  const [theme, setTheme] = useState<Theme>("auto");
  const [mounted, setMounted] = useState(false);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setMounted(true);
    const stored = localStorage.getItem("theme") as Theme | null;
    const initial = stored ?? "auto";
    setTheme(initial);
    applyTheme(initial);

    // Keep the resolved attribute in sync with OS changes while in "auto" mode.
    const media = window.matchMedia("(prefers-color-scheme: dark)");
    const handleChange = () => {
      if (!localStorage.getItem("theme")) {
        applyTheme("auto");
      }
    };
    media.addEventListener("change", handleChange);
    return () => media.removeEventListener("change", handleChange);
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
