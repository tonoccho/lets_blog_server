import { formatBytes, formatJobProgress, parseJobProgress } from "../jobProgress";

describe("parseJobProgress", () => {
  it("結果が無い(null・空文字)なら null", () => {
    expect(parseJobProgress(null)).toBeNull();
    expect(parseJobProgress("")).toBeNull();
  });

  it("JSON でない文字列は null", () => {
    expect(parseJobProgress("not json")).toBeNull();
  });

  it("phase が文字列でなければ(完了後の success / error の形)null", () => {
    expect(parseJobProgress('{"success":true}')).toBeNull();
    expect(parseJobProgress('{"phase":3}')).toBeNull();
  });

  it("phase だけなら、数値の項目は null", () => {
    expect(parseJobProgress('{"phase":"downloading"}')).toEqual({
      phase: "downloading",
      percent: null,
      bytesDone: null,
      bytesTotal: null,
    });
  });

  it("数値の項目を取り出し、数値でない項目は null にする", () => {
    expect(
      parseJobProgress('{"phase":"pulling","percent":40,"bytesDone":1024,"bytesTotal":4096}')
    ).toEqual({ phase: "pulling", percent: 40, bytesDone: 1024, bytesTotal: 4096 });
    expect(parseJobProgress('{"phase":"pulling","percent":"40","bytesDone":"x","bytesTotal":null}')).toEqual({
      phase: "pulling",
      percent: null,
      bytesDone: null,
      bytesTotal: null,
    });
  });
});

describe("formatJobProgress", () => {
  it("percent があれば「フェーズ N%」", () => {
    expect(formatJobProgress({ phase: "pulling", percent: 40, bytesDone: 1, bytesTotal: 2 })).toBe("pulling 40%");
  });

  it("percent が無く、バイト数が両方あって総量が正なら「完了 / 総量」", () => {
    expect(formatJobProgress({ phase: "pulling", percent: null, bytesDone: 1024, bytesTotal: 2048 })).toBe(
      "pulling 1.0 KB / 2.0 KB"
    );
  });

  it("総量が 0 なら、フェーズだけ", () => {
    expect(formatJobProgress({ phase: "pulling", percent: null, bytesDone: 0, bytesTotal: 0 })).toBe("pulling");
  });

  it("バイト数の片方が無ければ、フェーズだけ", () => {
    expect(formatJobProgress({ phase: "pulling", percent: null, bytesDone: null, bytesTotal: 10 })).toBe("pulling");
    expect(formatJobProgress({ phase: "pulling", percent: null, bytesDone: 10, bytesTotal: null })).toBe("pulling");
  });
});

describe("formatBytes", () => {
  it("0 以下は「0 B」", () => {
    expect(formatBytes(0)).toBe("0 B");
    expect(formatBytes(-5)).toBe("0 B");
  });

  it("1024 ごとに単位を上げ、最大は TB", () => {
    expect(formatBytes(512)).toBe("512.0 B");
    expect(formatBytes(1536)).toBe("1.5 KB");
    expect(formatBytes(5 * 1024 * 1024)).toBe("5.0 MB");
    expect(formatBytes(3 * 1024 ** 3)).toBe("3.0 GB");
    expect(formatBytes(2 * 1024 ** 4)).toBe("2.0 TB");
    expect(formatBytes(2048 * 1024 ** 4)).toBe("2048.0 TB");
  });
});
