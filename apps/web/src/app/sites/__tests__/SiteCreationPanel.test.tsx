import { fireEvent, render, screen } from "@testing-library/react";

jest.mock("../SiteForm", () => ({
  SiteForm: ({ defaultAdminPath }: { defaultAdminPath: string | null }) => <div>SITE_FORM:{String(defaultAdminPath)}</div>,
}));
jest.mock("../ManagedWordPressForm", () => ({ ManagedWordPressForm: () => <div>MANAGED_FORM</div> }));
import { SiteCreationPanel } from "../SiteCreationPanel";

describe("SiteCreationPanel(issue #1533: 既定の管理画面パスをSiteFormへ渡す)", () => {
  it("既存サイト登録フォームへ既定の管理画面パスを渡す", () => {
    render(<SiteCreationPanel users={[]} sites={[]} sshKeyPairs={[]} defaultAdminPath="wp-admin" />);
    expect(screen.getByText("SITE_FORM:wp-admin")).toBeInTheDocument();
  });

  it("「WordPressを新規構築」へ切り替えると構築フォームを表示する", () => {
    render(<SiteCreationPanel users={[]} sites={[]} sshKeyPairs={[]} defaultAdminPath={null} />);
    fireEvent.click(screen.getByText("WordPressを新規構築"));
    expect(screen.getByText("MANAGED_FORM")).toBeInTheDocument();
    expect(screen.queryByText(/SITE_FORM/)).toBeNull();
  });
});
