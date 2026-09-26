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
  Palette,
  type LucideIcon,
} from "lucide-react";

export type NavItem = {
  href: string;
  labelKey: string; // messages の nav 名前空間のキー
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
  Palette,
};

export const NAV_ITEMS: NavItem[] = [
  { href: "/", labelKey: "dashboard", icon: "Home" },
  { href: "/projects", labelKey: "projects", icon: "Folder" },
  { href: "/sites", labelKey: "sites", icon: "Globe" },
  { href: "/image-gallery", labelKey: "imageGallery", icon: "Images" },
  { href: "/operation-logs", labelKey: "operationLogs", icon: "History" },
];

export const ADMIN_NAV_ITEMS: NavItem[] = [
  { href: "/users", labelKey: "users", icon: "Users", adminOnly: true, group: "admin" },
  { href: "/admin/roles", labelKey: "roleManagement", icon: "Shield", adminOnly: true, group: "admin" },
  { href: "/admin/backup", labelKey: "dataBackup", icon: "DatabaseBackup", adminOnly: true, group: "admin" },
  { href: "/admin/system-settings", labelKey: "systemSettings", icon: "Settings", adminOnly: true, group: "admin" },
  { href: "/admin/ssh-keys", labelKey: "sshKeys", icon: "KeyRound", adminOnly: true, group: "admin" },
  // プロジェクト未紐付けサイト向けのグローバル既定タグデザイン(issue #763)。
  { href: "/admin/tag-design", labelKey: "tagDesign", icon: "Palette", adminOnly: true, group: "admin" },
];
