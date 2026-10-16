import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { AvatarUploadField } from "../AvatarUploadField";
import { installFetch, jsonResponse, resetServerRuntime, textResponse } from "@/lib/__tests__/testUtils/serverRuntime";
import { USER_SESSION } from "@/lib/__tests__/testUtils/webAdminRuntime";

// 自前の ../actions・@/lib/session・@/lib/apiClient は差し替えない。差し替えるのは Next.js の実行基盤と
// 外部との通信(fetch)だけ(#1833)。
jest.mock("server-only", () => ({}));
jest.mock("next/server", () => require("@/lib/__tests__/testUtils/serverRuntime").nextServerModule);
jest.mock("next/headers", () => require("@/lib/__tests__/testUtils/serverRuntime").nextHeadersModule);
jest.mock("next-auth/jwt", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthJwtModule);
jest.mock("next-auth", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthModule);
jest.mock("next/cache", () => ({ revalidatePath: jest.fn() }));
jest.mock("next/navigation", () => ({
  redirect: (path: string) => {
    throw new Error(`NEXT_REDIRECT:${path}`);
  },
}));

let fetchMock: jest.Mock;
/** identity-service へアップロードされたリクエストの本文(FormData)。 */
const uploads = (userId: number) =>
  fetchMock.mock.calls
    .filter(([url, init]) => String(url).endsWith(`/api/users/${userId}/avatar`) && init?.method === "POST")
    .map(([, init]) => init.body as FormData);

function installApi(userId: number, upload: () => Response = () => jsonResponse({ id: userId })) {
  fetchMock = installFetch({
    "GET /api/identity/me": () => jsonResponse({ id: userId }),
    [`POST /api/users/${userId}/avatar`]: upload,
  });
}

/**
 * issue #1241: プロフィール編集画面のアバターアップロード・切り抜きUI。
 *
 * <p>jsdomはcanvas 2D contextと画像デコードを実装しないため、
 * `HTMLCanvasElement.prototype.getContext`/`toBlob`と`<img>`の`naturalWidth`/
 * `naturalHeight`をこのファイル内でスタブする。
 */

const ORIGINAL_CREATE_OBJECT_URL = global.URL.createObjectURL;

/**
 * 正常系のcanvasスタブを(再)インストールする。一部のテストが異常系
 * (2Dコンテキスト取得失敗・toBlob失敗)を検証するために直接プロトタイプへ
 * 上書き代入するため、`beforeEach`で毎回インストールし直して他テストへ
 * 影響が漏れないようにする(jest.spyOn().mockRestore()は「既にjest.fn()で
 * 上書き済みのプロトタイプメソッド」に対しては元に戻せないことがあるため使わない)。
 */
function installGoodCanvasMocks() {
  HTMLCanvasElement.prototype.getContext = jest.fn(() => ({
    drawImage: jest.fn(),
  })) as unknown as typeof HTMLCanvasElement.prototype.getContext;

  HTMLCanvasElement.prototype.toBlob = jest.fn(function toBlob(
    this: HTMLCanvasElement,
    callback: BlobCallback
  ) {
    callback(new Blob(["fake-image-bytes"], { type: "image/png" }));
  }) as unknown as typeof HTMLCanvasElement.prototype.toBlob;
}

beforeAll(() => {
  global.URL.createObjectURL = jest.fn(() => "blob:mock-object-url");
});

afterAll(() => {
  global.URL.createObjectURL = ORIGINAL_CREATE_OBJECT_URL;
});

beforeEach(() => {
  jest.clearAllMocks();
  resetServerRuntime(USER_SESSION);
  installApi(1);
  installGoodCanvasMocks();
});

function pngFile(name: string, sizeBytes: number, type = "image/png"): File {
  const buffer = new ArrayBuffer(sizeBytes);
  return new File([buffer], name, { type });
}

/** ファイル選択後にレンダーされるプレビュー<img>へ、指定した自然サイズでloadイベントを発火する。 */
function loadSelectedImage(width: number, height: number) {
  const previewImg = screen.getByTestId("avatar-crop-image") as HTMLImageElement;
  Object.defineProperty(previewImg, "naturalWidth", { value: width, configurable: true });
  Object.defineProperty(previewImg, "naturalHeight", { value: height, configurable: true });
  fireEvent.load(previewImg);
}

describe("AvatarUploadField", () => {
  it("初期状態ではアバターURLが無ければプレビューを表示しない", () => {
    render(<AvatarUploadField userId={1} initialAvatarUrl={null} />);
    expect(screen.queryByTestId("avatar-preview")).not.toBeInTheDocument();
  });

  it("既存のアバターURLがあれば初期プレビューとして表示する", () => {
    render(<AvatarUploadField userId={1} initialAvatarUrl="https://example.com/a.png" />);
    expect(screen.getByTestId("avatar-preview")).toHaveAttribute("src", "https://example.com/a.png");
  });

  it("対応していない形式(GIF)を選択するとエラーが表示され切り抜きUIは出ない", () => {
    render(<AvatarUploadField userId={1} initialAvatarUrl={null} />);
    const input = screen.getByTestId("avatar-file-input") as HTMLInputElement;

    fireEvent.change(input, { target: { files: [pngFile("a.gif", 1024, "image/gif")] } });

    expect(screen.getByTestId("avatar-upload-error")).toHaveTextContent("対応していない画像形式");
    expect(screen.queryByTestId("avatar-crop-frame")).not.toBeInTheDocument();
    expect(input.value).toBe("");
  });

  it("20MBを超えるファイルを選択するとサイズ超過エラーが表示される", () => {
    render(<AvatarUploadField userId={1} initialAvatarUrl={null} />);
    const input = screen.getByTestId("avatar-file-input") as HTMLInputElement;

    fireEvent.change(input, { target: { files: [pngFile("big.png", 21 * 1024 * 1024)] } });

    expect(screen.getByTestId("avatar-upload-error")).toHaveTextContent("20MB");
  });

  it("800x400の画像を選択すると中央の400x400が初期選択された切り抜きUIが表示される", () => {
    render(<AvatarUploadField userId={1} initialAvatarUrl={null} />);
    const input = screen.getByTestId("avatar-file-input") as HTMLInputElement;

    fireEvent.change(input, { target: { files: [pngFile("wide.png", 1024)] } });
    loadSelectedImage(800, 400);

    const frame = screen.getByTestId("avatar-crop-frame");
    expect(frame).toHaveAttribute("data-crop-size", "400");
    expect(frame).toHaveAttribute("data-crop-x", "200");
    expect(frame).toHaveAttribute("data-crop-y", "0");
  });

  it("縦長画像でも中央の最大正方形が初期選択される", () => {
    render(<AvatarUploadField userId={1} initialAvatarUrl={null} />);
    const input = screen.getByTestId("avatar-file-input") as HTMLInputElement;

    fireEvent.change(input, { target: { files: [pngFile("tall.png", 1024)] } });
    loadSelectedImage(300, 900);

    const frame = screen.getByTestId("avatar-crop-frame");
    expect(frame).toHaveAttribute("data-crop-size", "300");
    expect(frame).toHaveAttribute("data-crop-x", "0");
    expect(frame).toHaveAttribute("data-crop-y", "300");
  });

  it("切り抜きを確定して保存に成功するとプレビューが配信URLへ切り替わり切り抜きUIが畳まれる", async () => {
    installApi(7);
    render(<AvatarUploadField userId={7} initialAvatarUrl={null} />);
    const input = screen.getByTestId("avatar-file-input") as HTMLInputElement;

    fireEvent.change(input, { target: { files: [pngFile("square.png", 1024)] } });
    loadSelectedImage(600, 600);

    fireEvent.click(screen.getByTestId("avatar-crop-confirm"));

    await waitFor(() => {
      expect(uploads(7)).toHaveLength(1);
    });
    const sent = uploads(7)[0].get("file") as File;
    expect(sent.name).toBe("avatar.png");
    expect(sent.type).toBe("image/png");
    await waitFor(() => {
      expect(screen.queryByTestId("avatar-crop-frame")).not.toBeInTheDocument();
    });
    const preview = screen.getByTestId("avatar-preview") as HTMLImageElement;
    expect(preview.src).toMatch(/^http:\/\/localhost\/api\/users\/7\/avatar\?v=\d+$/);
  });

  it("保存に失敗するとエラーが表示され切り抜きUIは残る", async () => {
    installApi(1, () => textResponse("対応していない画像形式です", 400));
    render(<AvatarUploadField userId={1} initialAvatarUrl={null} />);
    const input = screen.getByTestId("avatar-file-input") as HTMLInputElement;

    fireEvent.change(input, { target: { files: [pngFile("square.png", 1024)] } });
    loadSelectedImage(600, 600);

    fireEvent.click(screen.getByTestId("avatar-crop-confirm"));

    await waitFor(() => {
      expect(screen.getByTestId("avatar-upload-error")).toHaveTextContent("APIエラー (400): 対応していない画像形式です");
    });
    expect(screen.getByTestId("avatar-crop-frame")).toBeInTheDocument();
  });

  it("アバターURL入力欄はこのコンポーネントの外にあり関知しない(併存の確認は結合先で行う)", () => {
    render(<AvatarUploadField userId={1} initialAvatarUrl={null} />);
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
  });

  it("ファイル入力はラベルで紐付いたアクセシブルな名前を持つ(axeのlabelルール対策、issue #1358)", () => {
    render(<AvatarUploadField userId={1} initialAvatarUrl={null} />);

    const input = screen.getByLabelText("アバター画像") as HTMLInputElement;

    expect(input).toBe(screen.getByTestId("avatar-file-input"));
  });

  it("ファイル選択をキャンセルした場合(filesが空)は何も起きない", () => {
    render(<AvatarUploadField userId={1} initialAvatarUrl={null} />);
    const input = screen.getByTestId("avatar-file-input") as HTMLInputElement;

    fireEvent.change(input, { target: { files: [] } });

    expect(screen.queryByTestId("avatar-upload-error")).not.toBeInTheDocument();
    expect(screen.queryByTestId("avatar-crop-frame")).not.toBeInTheDocument();
  });

  it("canvasの2Dコンテキストを取得できない端末ではエラーを表示する", () => {
    HTMLCanvasElement.prototype.getContext = jest.fn(
      () => null
    ) as unknown as typeof HTMLCanvasElement.prototype.getContext;

    render(<AvatarUploadField userId={1} initialAvatarUrl={null} />);
    const input = screen.getByTestId("avatar-file-input") as HTMLInputElement;

    fireEvent.change(input, { target: { files: [pngFile("square.png", 1024)] } });
    loadSelectedImage(600, 600);
    fireEvent.click(screen.getByTestId("avatar-crop-confirm"));

    expect(screen.getByTestId("avatar-upload-error")).toHaveTextContent("画像の切り抜きに対応していません");
  });

  it("canvas.toBlobがnullを返した場合は画像生成エラーを表示する", async () => {
    HTMLCanvasElement.prototype.toBlob = jest.fn(function toBlobNull(
      this: HTMLCanvasElement,
      callback: BlobCallback
    ) {
      callback(null);
    }) as unknown as typeof HTMLCanvasElement.prototype.toBlob;

    render(<AvatarUploadField userId={1} initialAvatarUrl={null} />);
    const input = screen.getByTestId("avatar-file-input") as HTMLInputElement;

    fireEvent.change(input, { target: { files: [pngFile("square.png", 1024)] } });
    loadSelectedImage(600, 600);
    fireEvent.click(screen.getByTestId("avatar-crop-confirm"));

    await waitFor(() => {
      expect(screen.getByTestId("avatar-upload-error")).toHaveTextContent("画像の生成に失敗しました");
    });
  });

  it("保存中はボタンを無効にして「アップロード中…」と表示する", async () => {
    let release: (response: Response) => void = () => {};
    installApi(1, () => new Promise<Response>((resolve) => (release = resolve)) as unknown as Response);
    render(<AvatarUploadField userId={1} initialAvatarUrl={null} />);
    const input = screen.getByTestId("avatar-file-input") as HTMLInputElement;

    fireEvent.change(input, { target: { files: [pngFile("square.png", 1024)] } });
    loadSelectedImage(600, 600);
    expect(screen.getByTestId("avatar-crop-confirm")).toHaveTextContent("この内容でアバターとして保存");
    fireEvent.click(screen.getByTestId("avatar-crop-confirm"));

    await waitFor(() => expect(screen.getByTestId("avatar-crop-confirm")).toHaveTextContent("アップロード中…"));
    expect(screen.getByTestId("avatar-crop-confirm")).toBeDisabled();
    release(jsonResponse({ id: 1 }));
    await waitFor(() => expect(screen.queryByTestId("avatar-crop-frame")).not.toBeInTheDocument());
  });

  it("画像の読み込みが終わるまでは切り抜きの枠を出さず、保存ボタンも押せない", () => {
    render(<AvatarUploadField userId={1} initialAvatarUrl={null} />);
    const input = screen.getByTestId("avatar-file-input") as HTMLInputElement;

    fireEvent.change(input, { target: { files: [pngFile("square.png", 1024)] } });

    expect(screen.getByTestId("avatar-crop-image")).toBeInTheDocument();
    expect(screen.queryByTestId("avatar-crop-frame")).not.toBeInTheDocument();
    expect(screen.getByTestId("avatar-crop-confirm")).toBeDisabled();
  });
});
