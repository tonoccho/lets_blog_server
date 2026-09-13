import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { AvatarUploadField } from "../AvatarUploadField";
import * as actions from "../actions";

jest.mock("../actions", () => ({
  uploadAvatarAction: jest.fn(),
}));

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
    (actions.uploadAvatarAction as jest.Mock).mockResolvedValue({ success: true });
    render(<AvatarUploadField userId={7} initialAvatarUrl={null} />);
    const input = screen.getByTestId("avatar-file-input") as HTMLInputElement;

    fireEvent.change(input, { target: { files: [pngFile("square.png", 1024)] } });
    loadSelectedImage(600, 600);

    fireEvent.click(screen.getByTestId("avatar-crop-confirm"));

    await waitFor(() => {
      expect(actions.uploadAvatarAction).toHaveBeenCalledWith(7, {}, expect.any(FormData));
    });
    await waitFor(() => {
      expect(screen.queryByTestId("avatar-crop-frame")).not.toBeInTheDocument();
    });
    const preview = screen.getByTestId("avatar-preview") as HTMLImageElement;
    expect(preview.src).toMatch(/^http:\/\/localhost\/api\/users\/7\/avatar\?v=\d+$/);
  });

  it("保存に失敗するとエラーが表示され切り抜きUIは残る", async () => {
    (actions.uploadAvatarAction as jest.Mock).mockResolvedValue({ error: "対応していない画像形式です" });
    render(<AvatarUploadField userId={1} initialAvatarUrl={null} />);
    const input = screen.getByTestId("avatar-file-input") as HTMLInputElement;

    fireEvent.change(input, { target: { files: [pngFile("square.png", 1024)] } });
    loadSelectedImage(600, 600);

    fireEvent.click(screen.getByTestId("avatar-crop-confirm"));

    await waitFor(() => {
      expect(screen.getByTestId("avatar-upload-error")).toHaveTextContent("対応していない画像形式です");
    });
    expect(screen.getByTestId("avatar-crop-frame")).toBeInTheDocument();
  });

  it("アバターURL入力欄はこのコンポーネントの外にあり関知しない(併存の確認は結合先で行う)", () => {
    render(<AvatarUploadField userId={1} initialAvatarUrl={null} />);
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
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
});
