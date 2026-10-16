import { fireEvent, render, screen } from "@testing-library/react";
import { CustomLinksEditor } from "../CustomLinksEditor";

function hiddenValue(container: HTMLElement): unknown {
  return JSON.parse((container.querySelector('input[name="customLinks"]') as HTMLInputElement).value);
}

describe("CustomLinksEditor", () => {
  it("リンクが無ければ(null でも)入力欄を出さず、空の配列を hidden 入力に持つ", () => {
    const { container } = render(<CustomLinksEditor defaultLinks={null} />);

    expect(screen.queryByPlaceholderText("ラベル(例: 自分のブログ)")).not.toBeInTheDocument();
    expect(hiddenValue(container)).toEqual([]);
  });

  it("既存のリンクを入力欄に出す", () => {
    const { container } = render(<CustomLinksEditor defaultLinks={[{ label: "blog", url: "https://blog.example.com" }]} />);

    expect((screen.getByPlaceholderText("ラベル(例: 自分のブログ)") as HTMLInputElement).value).toBe("blog");
    expect((screen.getByPlaceholderText("https://example.com") as HTMLInputElement).value).toBe("https://blog.example.com");
    expect(hiddenValue(container)).toEqual([{ label: "blog", url: "https://blog.example.com" }]);
  });

  it("「+ 追加」で空の行を足し、ラベルと URL の編集が hidden 入力に反映される", () => {
    const { container } = render(<CustomLinksEditor defaultLinks={[]} />);

    fireEvent.click(screen.getByRole("button", { name: "+ 追加" }));
    fireEvent.change(screen.getByPlaceholderText("ラベル(例: 自分のブログ)"), { target: { value: "docs" } });
    fireEvent.change(screen.getByPlaceholderText("https://example.com"), { target: { value: "https://docs.example.com" } });

    expect(hiddenValue(container)).toEqual([{ label: "docs", url: "https://docs.example.com" }]);
  });

  it("「削除」でその行だけを除く", () => {
    const { container } = render(
      <CustomLinksEditor
        defaultLinks={[
          { label: "a", url: "https://a.example.com" },
          { label: "b", url: "https://b.example.com" },
        ]}
      />
    );

    fireEvent.click(screen.getAllByRole("button", { name: "削除" })[0]);

    expect(hiddenValue(container)).toEqual([{ label: "b", url: "https://b.example.com" }]);
  });
});
