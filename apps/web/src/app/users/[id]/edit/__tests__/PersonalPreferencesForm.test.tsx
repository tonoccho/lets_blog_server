import { render, screen } from "@testing-library/react";
import { PersonalPreferencesForm } from "../PersonalPreferencesForm";

jest.mock("../actions", () => ({
  updatePreferencesAction: jest.fn(),
}));

/**
 * issue #1259: 個人設定のタイムゾーンは任意の上書き。timezoneが`null`(未設定)のとき、
 * 「ブラウザに従う(未設定)」が選択された状態で表示されなければならない
 * (従来は`page.tsx`側で"Asia/Tokyo"にフォールバックしていたため、この状態を表現できなかった)。
 */
describe("PersonalPreferencesForm のタイムゾーン欄", () => {
  it("timezoneがnullなら「ブラウザに従う(未設定)」が選択されている", () => {
    render(
      <PersonalPreferencesForm
        locale="ja_JP"
        timezone={null}
        timezoneOptions={["Asia/Tokyo", "America/New_York"]}
      />
    );

    const select = screen.getByTestId("timezone-select") as HTMLSelectElement;
    expect(select.value).toBe("");
  });

  it("timezoneに値があればその値が選択されている", () => {
    render(
      <PersonalPreferencesForm
        locale="ja_JP"
        timezone="America/New_York"
        timezoneOptions={["Asia/Tokyo", "America/New_York"]}
      />
    );

    const select = screen.getByTestId("timezone-select") as HTMLSelectElement;
    expect(select.value).toBe("America/New_York");
  });

  it("「ブラウザに従う(未設定)」の選択肢が一覧の先頭にある", () => {
    render(
      <PersonalPreferencesForm
        locale="ja_JP"
        timezone={null}
        timezoneOptions={["Asia/Tokyo"]}
      />
    );

    const select = screen.getByTestId("timezone-select") as HTMLSelectElement;
    expect(select.options[0].value).toBe("");
    expect(select.options[0].textContent).toContain("ブラウザに従う");
  });
});
