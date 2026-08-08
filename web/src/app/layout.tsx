import type { Metadata } from "next";
import { Geist, Geist_Mono } from "next/font/google";
import { getSession } from "@/lib/session";
import { NAV_ITEMS, ADMIN_NAV_ITEMS } from "@/lib/navigation";
import { HeaderNav } from "./HeaderNav";
import { LogoutButton } from "./LogoutButton";
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
    >
      <body className="min-h-full flex flex-col bg-neutral-50 text-neutral-900">
        <header className="sticky top-0 z-40 border-b border-neutral-200 bg-white">
          <div className="mx-auto flex max-w-7xl items-center gap-6 px-4 py-3">
            <span className="shrink-0 font-semibold">Let&apos;s Blog Server</span>
            {session && <HeaderNav navItems={navItems} />}
            {session && (
              <div className="ml-auto flex shrink-0 items-center gap-4 text-sm">
                <span className="text-neutral-500">{session.user.email}</span>
                <LogoutButton />
              </div>
            )}
          </div>
        </header>
        <main className="mx-auto w-full max-w-5xl flex-1 px-4 py-8">{children}</main>
      </body>
    </html>
  );
}
