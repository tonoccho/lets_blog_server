import { render, screen } from "@testing-library/react";
import type { ReactNode } from "react";
import type { Site } from "@/lib/apiClient";

/**
 * issue #1363(親issue #1261 分割B): 「マウント前は固定プレースホルダーを表示する」
 * (#1362 と同じ mounted フラグ方式)をこのファイル単独で検証する。理由は
 * SshKeyPairsPanel.mountGate.test.tsx の先頭コメントと同じ(`"use client"`を持つ
 * コンポーネントでは`jest.spyOn(React, "useEffect")`が効かず、`jest.mock("react", ...)`
 * によるモジュール差し替えが必要。他のテストを巻き添えにしないためファイルを分離する)。
 */
jest.mock("react", () => ({
  __esModule: true,
  ...jest.requireActual("react"),
  useEffect: jest.fn(),
}));

jest.mock("@/lib/formatDate", () => ({
  formatDateTime: jest.fn(() => "FORMATTED_CREATED_AT"),
  TIMEZONE_PENDING_PLACEHOLDER: "読み込み中…",
}));

jest.mock("../DeleteSiteButton", () => ({
  DeleteSiteButton: () => <div>Delete Button</div>,
}));

jest.mock("../CheckConnectionButton", () => ({
  CheckConnectionButton: () => <button>Check Connection</button>,
}));

// `SiteListTable.tsx`はadmin向け操作列で`next/link`を使う。`next/link`は内部で
// `React.createContext`(router context)に依存しており、上の`jest.mock("react", ...)`が
// スプレッドで作る簡易モジュールではそれが欠落し、importするだけで例外になる
// (SiteListTable.test.tsxと同じ理由でモックに差し替える)。
jest.mock("next/link", () => {
  const Link = ({ children, href }: { children: ReactNode; href: string }) => <a href={href}>{children}</a>;
  Link.displayName = "MockLink";
  return Link;
});

import { SiteListTable } from "../SiteListTable";
import { formatDateTime } from "@/lib/formatDate";

function site(overrides: Partial<Site> = {}): Site {
  return {
    id: 1,
    siteKey: "test-site-1",
    name: "Test Site 1",
    baseUrl: "https://test1.example.com",
    cmsType: "WORDPRESS",
    managedWordpress: false,
    connectionCheckStatus: null,
    sshConfigured: false,
    createdAt: "2026-09-08T20:03:35",
    updatedAt: "2026-09-08T20:03:35",
    ...overrides,
  };
}

describe("SiteListTable(マウント前)", () => {
  it("個人設定TZが未設定のとき、マウント前は登録日に固定プレースホルダーを表示する(#1362と同じmountedフラグ方式、issue #1363)", () => {
    render(<SiteListTable sites={[site()]} projects={[]} isAdmin={false} timezone={null} adminPath="wp-admin" />);

    expect(formatDateTime).not.toHaveBeenCalled();
    expect(screen.queryByText("FORMATTED_CREATED_AT")).not.toBeInTheDocument();
    expect(screen.getByText("読み込み中…")).toBeInTheDocument();
  });
});
