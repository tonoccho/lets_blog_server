import { StrictMode } from "react";
import { act, render, screen } from "@testing-library/react";
import { signIn } from "next-auth/react";
import LoginPage from "../page";

/**
 * issue #1052: 自動リダイレクト(signIn)と、3秒経っても進まない場合の手動フォールバック表示の
 * 出し分けを検証する。ネイティブ<form action={Server Action}>が実際にJS無しでKeycloakまで
 * 到達することの検証は、jsdom上のコンポーネントテストでは再現できない
 * (`apps/web/src/app/setup/__tests__/SetupForm.test.tsx`と同じ理由)ため、
 * `apps/web/e2e/features/auth/login.feature`(javaScriptEnabled:falseでの実ブラウザ検証)が担う。
 *
 * issue #1393: React StrictMode 下での二重実行を固定する。受け入れテスト環境の `lbs-web` は
 * `next dev` で動いており、Next.js App Router の `reactStrictMode` は既定 true なので、
 * `useEffect(…, [])` は mount 時に2回実行される。ここを守らないと `signIn()` が2回発行され、
 * Keycloak 認可エンドポイントへのトップレベル遷移が2本競合する
 * (リリース検証 run 8 の実測: `GET /login` 291件に対し `POST /api/auth/signin/keycloak` 538件、
 * 2本目が `next-auth.state` クッキーを上書きするため `state mismatch` が44件)。
 *
 * 2件目のテストは #1052 の手動フォールバックを守るためにある。`useEffect` の本体全体を
 * ref でガードすると、StrictMode の2回目で `setTimeout` が張られず、フォールバックが
 * 永久に出なくなる。ガードしてよいのは `signIn` の発行だけである。
 */
jest.mock("next-auth/react", () => ({
  signIn: jest.fn(),
}));

jest.mock("../actions", () => ({
  startNoJsLoginAction: jest.fn(),
}));

const signInMock = signIn as jest.MockedFunction<typeof signIn>;

describe("LoginPage", () => {
  beforeEach(() => {
    signInMock.mockReset();
    jest.useFakeTimers();
  });

  afterEach(() => {
    jest.useRealTimers();
  });

  it("マウント時にKeycloakへのsignInを呼ぶ", () => {
    render(<LoginPage />);

    expect(signInMock).toHaveBeenCalledWith("keycloak", { callbackUrl: "/" });
  });

  it("3秒経つまでは手動フォールバックを表示しない", () => {
    render(<LoginPage />);

    expect(screen.queryByTestId("nojs-login-submit")).not.toBeInTheDocument();
  });

  it("自動リダイレクトが3秒で成立しなければ手動フォールバックを表示する", () => {
    render(<LoginPage />);

    act(() => {
      jest.advanceTimersByTime(3000);
    });

    expect(screen.getByTestId("nojs-login-submit")).toBeInTheDocument();
    expect(screen.getAllByText("JavaScript", { exact: false }).length).toBeGreaterThan(0);
  });

  it("アンマウント時にタイマーを解除する(アンマウント後のsetState警告防止)", () => {
    const { unmount } = render(<LoginPage />);

    unmount();

    expect(() => jest.advanceTimersByTime(3000)).not.toThrow();
  });
});

describe("LoginPage(React StrictMode 下、issue #1393)", () => {
  beforeEach(() => {
    signInMock.mockReset();
    jest.useFakeTimers();
  });

  afterEach(() => {
    jest.useRealTimers();
  });

  it("effectが二重実行されてもsignInは1回しか発行しない", () => {
    render(
      <StrictMode>
        <LoginPage />
      </StrictMode>
    );

    expect(signInMock).toHaveBeenCalledTimes(1);
    expect(signInMock).toHaveBeenCalledWith("keycloak", { callbackUrl: "/" });
  });

  it("signInをガードしても手動フォールバック(#1052)は3秒後に出る", () => {
    render(
      <StrictMode>
        <LoginPage />
      </StrictMode>
    );

    act(() => {
      jest.advanceTimersByTime(3000);
    });

    expect(screen.getByTestId("nojs-login-submit")).toBeInTheDocument();
  });
});
