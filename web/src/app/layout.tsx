import type { Metadata } from "next";
import Link from "next/link";
import { Geist, Geist_Mono } from "next/font/google";
import { getSession } from "@/lib/session";
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

const NAV_ITEMS = [
  { href: "/", label: "ダッシュボード" },
  { href: "/sites", label: "サイト" },
  { href: "/posts", label: "投稿履歴" },
  { href: "/ai-jobs", label: "AIジョブ" },
  { href: "/system", label: "システム" },
];

export default async function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  const session = await getSession();
  const navItems = session?.user.role === "admin" ? [...NAV_ITEMS, { href: "/users", label: "ユーザー" }] : NAV_ITEMS;

  return (
    <html
      lang="ja"
      className={`${geistSans.variable} ${geistMono.variable} h-full antialiased`}
    >
      <body className="min-h-full flex flex-col bg-neutral-50 text-neutral-900">
        <header className="border-b border-neutral-200 bg-white">
          <div className="mx-auto flex max-w-5xl items-center gap-6 px-4 py-3">
            <span className="font-semibold">Let&apos;s Blog Server</span>
            {session && (
              <nav className="flex gap-4 text-sm">
                {navItems.map((item) => (
                  <Link key={item.href} href={item.href} className="text-neutral-600 hover:text-neutral-900">
                    {item.label}
                  </Link>
                ))}
              </nav>
            )}
            {session && (
              <div className="ml-auto flex items-center gap-4 text-sm">
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
