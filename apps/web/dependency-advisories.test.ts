import { readFileSync } from "node:fs";
import path from "node:path";

/**
 * `apps/web` の依存ツリーに既知脆弱性のバージョンが再混入していないことを固定する(issue #1006)。
 *
 * このファイルが `src/` の外にあるのは、検査対象が `apps/web/package-lock.json` そのもので、
 * アプリケーションのモジュールではないからである(next.config.test.ts と同じ理由で
 * jest.config.ts の `<rootDir>/*.{test,spec}.*` に拾われる)。
 *
 * `npm audit` と役割が重なるが、こちらはレジストリへ問い合わせずロックファイルだけで完結する。
 * lockfile を再生成した拍子に古いバージョンへ戻る事故を、ネットワーク無しで検知するのが目的。
 * 新しい勧告が出たら `ADVISORIES` に足す。解消したものを消してはいけない(再混入の検知が消える)。
 */

type Advisory = {
  /** npm 上のパッケージ名 */
  readonly pkg: string;
  /** GitHub Advisory ID */
  readonly id: string;
  /** 勧告が示す脆弱なバージョン範囲(npm audit の出力そのまま) */
  readonly range: string;
  /** そのバージョンが脆弱なら true */
  readonly vulnerable: (version: string) => boolean;
};

/** `1.2.3-beta.1` のようなプレリリース識別子を落として major/minor/patch を数値で返す。 */
function parseVersion(version: string): [number, number, number] {
  const core = version.split("-")[0].split("+")[0];
  const parts = core.split(".").map((n) => Number.parseInt(n, 10));
  return [parts[0] || 0, parts[1] || 0, parts[2] || 0];
}

/** semver の大小比較。a < b なら負、a === b なら 0、a > b なら正。 */
function compareVersions(a: string, b: string): number {
  const left = parseVersion(a);
  const right = parseVersion(b);
  for (let i = 0; i < 3; i++) {
    if (left[i] !== right[i]) {
      return left[i] - right[i];
    }
  }
  return 0;
}

const atMost = (bound: string) => (version: string) => compareVersions(version, bound) <= 0;

const between = (low: string, high: string) => (version: string) =>
  compareVersions(version, low) >= 0 && compareVersions(version, high) <= 0;

const ADVISORIES: readonly Advisory[] = [
  {
    pkg: "@orval/core",
    id: "GHSA-h526-wf6g-67jv",
    range: "<=7.18.0",
    vulnerable: atMost("7.18.0"),
  },
  {
    pkg: "@orval/fetch",
    id: "GHSA-h526-wf6g-67jv",
    range: "<=7.18.0 || 8.6.0",
    vulnerable: (version) => atMost("7.18.0")(version) || compareVersions(version, "8.6.0") === 0,
  },
  {
    pkg: "esbuild",
    id: "GHSA-67mh-4wv8-2f99",
    range: "<=0.24.2",
    vulnerable: atMost("0.24.2"),
  },
  {
    pkg: "js-yaml",
    id: "GHSA-5p4m-2wfm-xmqj",
    range: "4.0.0 - 4.3.0",
    vulnerable: between("4.0.0", "4.3.0"),
  },
  {
    pkg: "lodash",
    id: "GHSA-r5fr-rjxr-66jc / GHSA-f23m-r3pf-42rh",
    range: "<=4.17.23",
    vulnerable: atMost("4.17.23"),
  },
];

/** 生成スクリプトと apps/web の双方でバージョンを揃える対象。 */
const ORVAL_PACKAGES = ["@orval/core", "@orval/fetch"] as const;

/**
 * `scripts/generate-api-client.sh` が固定している orval CLI のバージョンを読み取る。
 *
 * 読み取れないまま `undefined === undefined` で通ってしまうと、検査したつもりで何も
 * 検査していないことになる(#994 と同型の事故)。そのため代入が1件見つからない場合と、
 * 取れた値がバージョンの形をしていない場合は、その場で明示的に失敗させる。
 */
function pinnedOrvalVersion(): string {
  const script = path.join(__dirname, "..", "..", "scripts", "generate-api-client.sh");
  const source = readFileSync(script, "utf8");
  // 行頭の代入だけを拾う。コメント中の `${ORVAL_VERSION}` などに引っかからないようにする。
  const matches = [...source.matchAll(/^ORVAL_VERSION="([^"]*)"/gm)];
  if (matches.length !== 1) {
    throw new Error(
      `scripts/generate-api-client.sh から ORVAL_VERSION の代入を1件だけ読み取れなかった` +
        `(見つかった数: ${matches.length})。スクリプト側の書き方を変えたなら、` +
        `この抽出も合わせて直すこと。`,
    );
  }
  const version = matches[0][1];
  if (!/^\d+\.\d+\.\d+(?:[-+].*)?$/.test(version)) {
    throw new Error(
      `scripts/generate-api-client.sh の ORVAL_VERSION がバージョンとして読めない: "${version}"`,
    );
  }
  return version;
}

type LockEntry = { name?: string; version?: string };

/** lockfile のキー(`node_modules/a/node_modules/b`)から実際のパッケージ名を取り出す。 */
function packageNameOf(key: string, entry: LockEntry): string | null {
  if (entry.name) {
    return entry.name;
  }
  const marker = "node_modules/";
  const at = key.lastIndexOf(marker);
  if (at < 0) {
    return null;
  }
  return key.slice(at + marker.length);
}

function installedPackages(): ReadonlyArray<{ key: string; name: string; version: string }> {
  const lockfile = path.join(__dirname, "package-lock.json");
  const lock = JSON.parse(readFileSync(lockfile, "utf8")) as {
    packages?: Record<string, LockEntry>;
  };
  const packages = lock.packages ?? {};
  const found: Array<{ key: string; name: string; version: string }> = [];
  for (const [key, entry] of Object.entries(packages)) {
    const name = packageNameOf(key, entry);
    if (!name || !entry.version) {
      continue;
    }
    found.push({ key, name, version: entry.version });
  }
  return found;
}

describe("apps/web の package-lock.json", () => {
  const installed = installedPackages();

  it("ロックファイルを読めている", () => {
    // 以下の検査が「1件も見つからなかったから空だった」で通ってしまわないよう、
    // パースが成功して中身があることを先に確かめる。
    expect(installed.length).toBeGreaterThan(100);
  });

  it.each(ADVISORIES.map((a) => [a.pkg, a] as const))(
    "%s に既知脆弱性のバージョンを含まない",
    (_pkg, advisory) => {
      const offenders = installed
        .filter((p) => p.name === advisory.pkg && advisory.vulnerable(p.version))
        .map((p) => `${p.key}@${p.version}`);
      expect(offenders).toEqual([]);
    },
  );

  it("orval 系は生成スクリプトが固定している CLI と厳密に同じバージョンを宣言している", () => {
    // scripts/generate-api-client.sh が npx で固定して呼ぶ orval と、apps/web が devDependencies
    // として宣言する @orval/* がずれていると、監査対象と実際に動くコードが別物になる(#1006)。
    // 「メジャーが 8 以上」では、片方だけを上げた場合(8.27.0 と 8.99.0)を見逃す。
    // 文書(docs/API_CLIENT_GENERATION.md「5. orval のバージョンは固定されている」)が
    // 主張しているのは厳密一致なので、ここでも厳密一致を検査する。
    const pinned = pinnedOrvalVersion();
    const manifest = JSON.parse(
      readFileSync(path.join(__dirname, "package.json"), "utf8"),
    ) as { devDependencies?: Record<string, string> };
    const declared = manifest.devDependencies ?? {};

    // 勧告 GHSA-h526-wf6g-67jv の修正が入るのは 8.x 系である。スクリプトと package.json を
    // 揃えて 7.x へ戻した場合は「一致している」だけでは気づけないため、下限も見る。
    expect(parseVersion(pinned)[0]).toBeGreaterThanOrEqual(8);

    // 失敗時に「スクリプト側の値」と「package.json 側の値」の両方が出るようにする。
    // どちらを直すべきかはメッセージだけでは決まらないので、両方を示すのが要点。
    const mismatches = ORVAL_PACKAGES.flatMap((pkg) => {
      const spec = declared[pkg];
      if (spec === pinned) {
        return [];
      }
      return [
        `${pkg}: apps/web/package.json は "${spec ?? "(未宣言)"}" を宣言しているが、` +
          `scripts/generate-api-client.sh の ORVAL_VERSION は "${pinned}"`,
      ];
    });
    expect(mismatches).toEqual([]);
  });
});
