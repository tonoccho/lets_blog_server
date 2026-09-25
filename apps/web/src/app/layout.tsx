import type { Metadata } from "next";
import { Geist, Geist_Mono } from "next/font/google";
import { getSession } from "@/lib/session";
import { NAV_ITEMS, ADMIN_NAV_ITEMS } from "@/lib/navigation";
import { HeaderNav } from "./HeaderNav";
import { LogoutButton } from "./LogoutButton";
import { ThemeSwitcher } from "./ThemeSwitcher";
import { LanguageSwitcher } from "./LanguageSwitcher";
import { VscodeExtensionDownloadIconButton } from "./VscodeExtensionDownloadIconButton";
import { I18nProvider } from "./I18nProvider";
import { SessionProvider } from "./SessionProvider";
import "./globals.css";

const geistSans = Geist({
  variable: "--font-geist-sans",
  subsets: ["latin"],
});

const geistMono = Geist_Mono({
  variable: "--font-geist-mono",
  subsets: ["latin"],
});

export const metadata: Metadata = {
  title: "Let's Blog Server 管理画面",
  description: "サイト登録・投稿履歴・AIジョブ状況を管理する画面",
};

export default async function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  const session = await getSession();
  const navItems =
    session?.user.role === "admin" ? [...NAV_ITEMS, ...ADMIN_NAV_ITEMS] : NAV_ITEMS;

  return (
    <html
      lang="ja"
      className={`${geistSans.variable} ${geistMono.variable} h-full antialiased`}
      suppressHydrationWarning
    >
      <head>
        <script
          // Resolve the theme before first paint to avoid a flash of the wrong theme.
          // Mirrors ThemeSwitcher's resolveEffectiveTheme logic.
          dangerouslySetInnerHTML={{
            __html: `(function(){try{var t=localStorage.getItem("theme");var d=t?t==="dark":window.matchMedia("(prefers-color-scheme: dark)").matches;document.documentElement.setAttribute("data-theme",d?"dark":"light");}catch(e){}})();`,
          }}
        />
      </head>
      <body className="min-h-full flex flex-col bg-neutral-50 text-neutral-900 dark:bg-neutral-950 dark:text-neutral-50">
        <SessionProvider session={session}>
          <I18nProvider>
            <header className="sticky top-0 z-40 border-b border-neutral-200 bg-white dark:border-neutral-800 dark:bg-neutral-900">
              <div className="mx-auto max-w-7xl px-4">
                <div className="flex items-center gap-6 py-3">
                  <span className="shrink-0 font-semibold">Let&apos;s Blog Server</span>
                  {session && <HeaderNav navItems={navItems} />}
                </div>
                {session && (
                  <div className="flex items-center justify-end gap-4 border-t border-neutral-200 px-0 py-3 text-sm dark:border-neutral-800">
                    <span className="text-neutral-500 dark:text-neutral-400">{session.user.email}</span>
                    <LanguageSwitcher />
                    <ThemeSwitcher />
                    <LogoutButton />
                    <VscodeExtensionDownloadIconButton />
                  </div>
                )}
              </div>
            </header>
            <main className="mx-auto w-full max-w-5xl flex-1 px-4 py-8">{children}</main>
          </I18nProvider>
        </SessionProvider>
      </body>
    </html>
  );
}
