/**
 * @jest-environment jsdom
 */
import { render } from "@testing-library/react";
import { renderToString } from "react-dom/server";
import { BrowserTimeZoneField } from "../BrowserTimeZoneField";

function mockBrowserTimeZone(timeZone: string) {
  jest
    .spyOn(Intl.DateTimeFormat.prototype, "resolvedOptions")
    .mockReturnValue({ timeZone } as Intl.ResolvedDateTimeFormatOptions);
}

describe("BrowserTimeZoneField(issue #1437)", () => {
  afterEach(() => jest.restoreAllMocks());

  it("個人設定TZが未設定なら、ブラウザTZを name=tz の hidden 入力として持つ", () => {
    mockBrowserTimeZone("Pacific/Auckland");
    const { container } = render(<BrowserTimeZoneField personalTimeZone={null} />);
    const input = container.querySelector('input[name="tz"]') as HTMLInputElement;
    expect(input.type).toBe("hidden");
    expect(input.value).toBe("Pacific/Auckland");
  });

  it("個人設定TZがあれば何も描画しない(送る必要がない)", () => {
    mockBrowserTimeZone("Pacific/Auckland");
    const { container } = render(<BrowserTimeZoneField personalTimeZone="Asia/Tokyo" />);
    expect(container.querySelector('input[name="tz"]')).toBeNull();
  });

  it("サーバー描画ではブラウザTZが分からないので値は空(ハイドレーション不一致を起こさない)", () => {
    mockBrowserTimeZone("Pacific/Auckland");
    const html = renderToString(<BrowserTimeZoneField personalTimeZone={null} />);
    expect(html).not.toContain("Pacific/Auckland");
  });
});
