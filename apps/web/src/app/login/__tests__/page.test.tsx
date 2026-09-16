import { act, render, screen } from "@testing-library/react";
import { signIn } from "next-auth/react";
import LoginPage from "../page";

/**
 * issue #1052: 自動リダイレクト(signIn)と、3秒経っても進まない場合の手動フォールバック表示の
 * 出し分けを検証する。ネイティブ<form action={Server Action}>が実際にJS無しでKeycloakまで
 * 到達することの検証は、jsdom上のコンポーネントテストでは再現できない
 * (`apps/web/src/app/setup/__tests__/SetupForm.test.tsx`と同じ理由)ため、
 * `apps/web/e2e/features/auth/login.feature`(javaScriptEnabled:falseでの実ブラウザ検証)が担う。
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
