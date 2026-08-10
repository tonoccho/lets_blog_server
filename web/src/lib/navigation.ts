import {
  Home,
  Folder,
  Globe,
  Users,
  FileText,
  Zap,
  Settings,
  Lock,
  ScrollText,
  Shield,
  Images,
  DatabaseBackup,
  KeyRound,
  History,
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
  Zap,
  Settings,
  Lock,
  ScrollText,
  Shield,
  Images,
  DatabaseBackup,
  KeyRound,
  History,
};

export const NAV_ITEMS: NavItem[] = [
  { href: "/", label: "ダッシュボード", icon: "Home" },
  { href: "/projects", label: "プロジェクト", icon: "Folder" },
  { href: "/sites", label: "サイト", icon: "Globe" },
  { href: "/posts", label: "投稿履歴", icon: "FileText" },
  { href: "/ai-jobs", label: "AIジョブ", icon: "Zap" },
  { href: "/image-gallery", label: "生成画像ギャラリー", icon: "Images" },
  { href: "/operation-logs", label: "操作ログ", icon: "History" },
  { href: "/system", label: "システム", icon: "Settings" },
  { href: "/settings/security", label: "セキュリティ設定", icon: "Lock" },
];

export const ADMIN_NAV_ITEMS: NavItem[] = [
  { href: "/users", label: "ユーザー", icon: "Users", adminOnly: true, group: "admin" },
  { href: "/audit-logs", label: "監査ログ", icon: "ScrollText", adminOnly: true, group: "admin" },
  { href: "/admin/roles", label: "ロール管理", icon: "Shield", adminOnly: true, group: "admin" },
  { href: "/admin/backup", label: "データバックアップ", icon: "DatabaseBackup", adminOnly: true, group: "admin" },
  { href: "/admin/settings", label: "システム設定", icon: "KeyRound", adminOnly: true, group: "admin" },
];
