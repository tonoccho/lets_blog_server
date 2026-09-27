"use client";

import { useRef } from "react";
import { useServerInsertedHTML } from "next/navigation";

/**
 * Resolves the theme before first paint to avoid a flash of the wrong theme.
 * Mirrors ThemeSwitcher's resolveEffectiveTheme logic.
 */
export const THEME_INIT_SCRIPT = `(function(){try{var t=localStorage.getItem("theme");var d=t?t==="dark":window.matchMedia("(prefers-color-scheme: dark)").matches;document.documentElement.setAttribute("data-theme",d?"dark":"light");}catch(e){}})();`;

/**
 * Emits the theme-init script into the HTML stream (just before </head>) instead of
 * rendering a <script> in the React tree. React 19 never executes <script> elements
 * that the client renderer creates and warns about them (#1238).
 * On the client `useServerInsertedHTML` is a no-op, so nothing is rendered there.
 * Next invokes the callback once per flushed chunk, so it emits only on the first call of a
 * render; the flag lives in the component instance (not module scope) so it never leaks across requests.
 */
export function ThemeScript() {
  const emitted = useRef(false);
  useServerInsertedHTML(() => {
    if (emitted.current) return null;
    emitted.current = true;
    return <script dangerouslySetInnerHTML={{ __html: THEME_INIT_SCRIPT }} />;
  });
  return null;
}
