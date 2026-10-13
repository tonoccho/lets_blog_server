import { readBulkJobView } from "../bulkJobResult";

const log = { environment: "local", status: "SUCCESS" };

describe("readBulkJobView(issue #1702)", () => {
  it("done のジョブは result_payload.logs を環境ごとの結果として返す", () => {
    const view = readBulkJobView({
      status: "done",
      resultPayload: JSON.stringify({ phase: "done", projectId: 7, logs: [log] }),
    });
    expect(view).toEqual({ kind: "done", logs: [log] });
  });

  it.each([
    ["payloadが無い", null],
    ["JSONでない", "{"],
    ["logsが配列でない", JSON.stringify({ logs: "x" })],
    ["logsが無い", JSON.stringify({ phase: "done" })],
    ["JSONがnull", "null"],
  ])("done でも結果が読めない(%s)ときは invalid", (_label, resultPayload) => {
    expect(readBulkJobView({ status: "done", resultPayload })).toEqual({ kind: "invalid" });
  });

  it("failed のジョブは error の文言を返す", () => {
    expect(
      readBulkJobView({ status: "failed", resultPayload: JSON.stringify({ error: "待ち行列が満杯です", errorType: "queue_full" }) })
    ).toEqual({ kind: "failed", error: "待ち行列が満杯です" });
  });

  it.each([
    ["payloadが無い", null],
    ["JSONでない", "{"],
    ["errorが無い", JSON.stringify({ errorType: "unexpected_error" })],
    ["errorが文字列でない", JSON.stringify({ error: 3 })],
  ])("failed で理由が読めない(%s)ときは既定の文言", (_label, resultPayload) => {
    expect(readBulkJobView({ status: "failed", resultPayload })).toEqual({
      kind: "failed",
      error: "全環境インストールに失敗しました。",
    });
  });

  it.each(["running", "pending"])("%s のジョブはまだ実行中", (status) => {
    expect(readBulkJobView({ status, resultPayload: null })).toEqual({ kind: "running" });
  });
});
