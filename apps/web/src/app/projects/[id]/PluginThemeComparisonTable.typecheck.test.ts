/**
 * @jest-environment node
 */
// jest(SWC)は型を消すため、型の import 漏れ(#1588: StatusComparisonRow)は単体テストでは見えない。
// このファイルを TypeScript の型検査にかけ、診断が 0 件であることを確かめる。
import path from "path";
import ts from "typescript";

describe("PluginThemeComparisonTable.tsx の型検査", () => {
  it("型エラーを出さない", () => {
    const root = path.resolve(__dirname, "../../../..");
    const configPath = path.join(root, "tsconfig.json");
    const raw = ts.readConfigFile(configPath, ts.sys.readFile);
    const parsed = ts.parseJsonConfigFileContent(raw.config, ts.sys, root);
    const options: ts.CompilerOptions = { ...parsed.options, incremental: false, noEmit: true };
    const target = path.join(__dirname, "PluginThemeComparisonTable.tsx");
    const program = ts.createProgram([target], options);
    const diagnostics = ts
      .getPreEmitDiagnostics(program, program.getSourceFile(target))
      .map((d) => ts.flattenDiagnosticMessageText(d.messageText, "\n"));
    expect(diagnostics).toEqual([]);
  }, 120000);
});
