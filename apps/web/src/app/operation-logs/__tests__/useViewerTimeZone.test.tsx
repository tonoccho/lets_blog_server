import { renderHook } from "@testing-library/react";
import { useViewerTimeZone } from "../useViewerTimeZone";

describe("useViewerTimeZone(issue #1260)", () => {
  afterEach(() => jest.restoreAllMocks());

  function mockResolved(impl: () => Intl.ResolvedDateTimeFormatOptions) {
    jest.spyOn(Intl.DateTimeFormat.prototype, "resolvedOptions").mockImplementation(impl);
  }

  it("個人設定TZがあればブラウザTZより優先する", () => {
    mockResolved(() => ({ timeZone: "Pacific/Auckland" }) as Intl.ResolvedDateTimeFormatOptions);
    expect(renderHook(() => useViewerTimeZone("Asia/Tokyo")).result.current).toBe("Asia/Tokyo");
  });

  it("個人設定TZが未設定ならブラウザTZを返す", () => {
    mockResolved(() => ({ timeZone: "America/New_York" }) as Intl.ResolvedDateTimeFormatOptions);
    expect(renderHook(() => useViewerTimeZone(null)).result.current).toBe("America/New_York");
  });

  it("ブラウザTZが空文字ならnull(未解決)を返す", () => {
    mockResolved(() => ({ timeZone: "" }) as Intl.ResolvedDateTimeFormatOptions);
    expect(renderHook(() => useViewerTimeZone(null)).result.current).toBeNull();
  });

  it("ブラウザTZの取得が例外を投げてもnull(未解決)を返し、個人設定TZがあればそれを使う", () => {
    mockResolved(() => {
      throw new Error("no Intl");
    });
    expect(renderHook(() => useViewerTimeZone(null)).result.current).toBeNull();
    expect(renderHook(() => useViewerTimeZone("Asia/Tokyo")).result.current).toBe("Asia/Tokyo");
  });
});
