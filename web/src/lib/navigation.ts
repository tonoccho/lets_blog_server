import {
  Home,
  Folder,
  Globe,
  Users,
  FileText,
  Shield,
  Images,
  DatabaseBackup,
  History,
  Settings,
  KeyRound,
  type LucideIcon,
} from "lucide-react";

export type NavItem = {
  href: string;
  label: string;
  icon: string;
  adminOnly?: boolean;
  group?: "admin"; // ドロップダウングループの指定
};

export const ICON_MAP: Record<string, LucideIcon> = {
  Home,
  Folder,
  Globe,
  Users,
  FileText,
  Shield,
  Images,
  DatabaseBackup,
  History,
  Settings,
  KeyRound,
};

export const NAV_ITEMS: NavItem[] = [
  { href: "/", label: "ダッシュボード", icon: "Home" },
  { href: "/projects", label: "プロジェクト", icon: "Folder" },
  { href: "/sites", label: "サイト", icon: "Globe" },
  { href: "/image-gallery", label: "生成画像ギャラリー", icon: "Images" },
  { href: "/operation-logs", label: "操作ログ", icon: "History" },
];

export const ADMIN_NAV_ITEMS: NavItem[] = [
  { href: "/users", label: "ユーザー", icon: "Users", adminOnly: true, group: "admin" },
  { href: "/admin/roles", label: "ロール管理", icon: "Shield", adminOnly: true, group: "admin" },
  { href: "/admin/backup", label: "データバックアップ", icon: "DatabaseBackup", adminOnly: true, group: "admin" },
  { href: "/admin/system-settings", label: "システム設定", icon: "Settings", adminOnly: true, group: "admin" },
  { href: "/admin/ssh-keys", label: "SSH鍵管理", icon: "KeyRound", adminOnly: true, group: "admin" },
];
