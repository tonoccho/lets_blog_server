import type { Metadata } from "next";
import { Geist, Geist_Mono } from "next/font/google";
import { getSession } from "@/lib/session";
import { NAV_ITEMS, ADMIN_NAV_ITEMS } from "@/lib/navigation";
import { Footer } from "./Footer";
import { SideNav, SideNavProvider, SideNavToggle } from "./SideNav";
import { LogoutButton } from "./LogoutButton";
import { ThemeSwitcher } from "./ThemeSwitcher";
import { ThemeScript } from "./ThemeScript";
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
      <body className="min-h-full flex flex-col bg-neutral-50 text-neutral-900 dark:bg-neutral-950 dark:text-neutral-50">
        <ThemeScript />
        <SessionProvider session={session}>
          <I18nProvider>
            <SideNavProvider>
              <header className="sticky top-0 z-40 border-b border-neutral-200 bg-white dark:border-neutral-800 dark:bg-neutral-900">
                <div className="px-4">
                  <div className="flex items-center gap-6 py-3">
                    {session && <SideNavToggle />}
                    <span className="shrink-0 font-semibold">Let&apos;s Blog Server</span>
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
              <div className="flex min-w-0 flex-1">
                {session && <SideNav navItems={navItems} />}
                <main className="min-w-0 w-full flex-1 px-4 py-8">{children}</main>
              </div>
              <Footer />
            </SideNavProvider>
          </I18nProvider>
        </SessionProvider>
      </body>
    </html>
  );
}
