import { render, screen } from "@testing-library/react";
import { DisplayNameSelect } from "../DisplayNameSelect";

function options(): string[] {
  return Array.from((screen.getByLabelText("表示名") as HTMLSelectElement).options).map((o) => o.value);
}

const base = { firstName: "", lastName: "", nickname: "", email: "", defaultValue: null };

describe("DisplayNameSelect", () => {
  it("入力が何も無ければ「選択してください」だけを出し、未選択にする", () => {
    render(<DisplayNameSelect {...base} />);

    expect(options()).toEqual([""]);
    expect((screen.getByLabelText("表示名") as HTMLSelectElement).value).toBe("");
  });

  it("ニックネーム・名・姓・両方の並び・メールのローカル部を候補にする(重複は除く)", () => {
    render(
      <DisplayNameSelect firstName="太郎" lastName="山田" nickname="taro" email="taro@example.com" defaultValue={null} />
    );

    expect(options()).toEqual(["", "taro", "太郎", "山田", "太郎 山田", "山田 太郎"]);
  });

  it("メールのローカル部がニックネームと同じなら、候補に重複して出さない", () => {
    render(<DisplayNameSelect {...base} nickname="taro" email="taro@example.com" />);

    expect(options()).toEqual(["", "taro"]);
  });

  it("名だけ・姓だけのときは、並べた候補を作らない", () => {
    const { rerender } = render(<DisplayNameSelect {...base} firstName="太郎" />);
    expect(options()).toEqual(["", "太郎"]);

    rerender(<DisplayNameSelect {...base} lastName="山田" />);
    expect(options()).toEqual(["", "山田"]);
  });

  it("保存済みの表示名が候補に無ければ先頭に足して選択する", () => {
    render(<DisplayNameSelect {...base} nickname="taro" defaultValue="旧表示名" />);

    expect(options()).toEqual(["", "旧表示名", "taro"]);
    expect((screen.getByLabelText("表示名") as HTMLSelectElement).value).toBe("旧表示名");
  });

  it("保存済みの表示名が候補にあれば、足さずにそれを選択する", () => {
    render(<DisplayNameSelect {...base} nickname="taro" defaultValue="taro" />);

    expect(options()).toEqual(["", "taro"]);
    expect((screen.getByLabelText("表示名") as HTMLSelectElement).value).toBe("taro");
  });
});
