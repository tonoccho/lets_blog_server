import {
  Home,
  Folder,
  Globe,
  Users,
  FileText,
  Zap,
  Settings,
  Lock,
  Tag,
  ScrollText,
  Shield,
  Images,
  DatabaseBackup,
  KeyRound,
  type LucideIcon,
} from "lucide-react";

export type NavItem = {
  href: string;
  label: string;
  icon: LucideIcon;
  adminOnly?: boolean;
};

export const NAV_ITEMS: NavItem[] = [
  { href: "/", label: "ダッシュボード", icon: Home },
  { href: "/projects", label: "プロジェクト", icon: Folder },
  { href: "/sites", label: "サイト", icon: Globe },
  { href: "/posts", label: "投稿履歴", icon: FileText },
  { href: "/ai-jobs", label: "AIジョブ", icon: Zap },
  { href: "/image-gallery", label: "生成画像ギャラリー", icon: Images },
  { href: "/system", label: "システム", icon: Settings },
  { href: "/settings/security", label: "セキュリティ設定", icon: Lock },
];

export const ADMIN_NAV_ITEMS: NavItem[] = [
  { href: "/users", label: "ユーザー", icon: Users, adminOnly: true },
  { href: "/audit-logs", label: "監査ログ", icon: ScrollText, adminOnly: true },
  { href: "/admin/roles", label: "ロール管理", icon: Shield, adminOnly: true },
  { href: "/custom-tags", label: "カスタムタグ", icon: Tag, adminOnly: true },
  { href: "/admin/backup", label: "データバックアップ", icon: DatabaseBackup, adminOnly: true },
  { href: "/admin/settings", label: "システム設定", icon: KeyRound, adminOnly: true },
];
