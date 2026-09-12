import { execFileSync } from 'node:child_process';
import path from 'node:path';
import { releaseAcceptanceTestLock } from './at-lock';

/**
 * E2E終了後のテストデータ後片付け(issue #588)。
 *
 * 各specはafterEach/afterAllでUI経由の後片付けを行うが、フィクスチャ作成の途中で
 * テストが落ちた場合や、UIに削除機能が無いテーブル(生成画像のシーケンス等)には
 * 孤児行が残る。サービス分割(#570)後はそれが複数スキーマに散らばるため、
 * scripts/e2e-cleanup-test-data.sh で横断的に掃除する。
 *
 * DBを直接変更する操作なので、既定では実行しない。E2E_DB_CLEANUP=1 が指定された場合のみ
 * 実行する(CIのように毎回使い捨てにできる環境向け)。未指定時はドライラン相当の案内を出す。
 *
 * issue #945 (AT-19): **受け入れテスト(`npm run test:at:clean`)ではこの後片付けは要らない。**
 * 受け入れテストは実行の「前」に scripts/reset-acceptance-env.sh で全部消してから始めるため、
 * 終了時に残っていても次回の実行には影響しない。むしろ残しておいたほうが失敗の調査ができる。
 *
 * この変数を残しているのは、`.feature` へ未移行の Playwright spec(apps/web/e2e/*.spec.ts)が
 * 「既存データを壊さない一意なフィクスチャ」という逆の前提で書かれており、その孤児行の
 * 掃除には依然として必要だから。全 spec の移行が終わった時点で、この teardown ごと削除する。
 *
 * issue #1187: `globalSetup` が獲得した受け入れテストの排他ロックを、実行の完了時に
 * 必ず解放する(自プロセスが保持していなければ何もしない冪等な呼び出しなので、
 * `globalSetup` がロック獲得より前で失敗した場合でも安全)。Playwrightは`globalSetup`が
 * 途中で失敗した場合でも`globalTeardown`を呼ぶため、ここに置けば取りこぼしがない。
 */
export default async function globalTeardown(): Promise<void> {
  const repoRoot = path.resolve(__dirname, '..', '..', '..');
  const script = path.join(repoRoot, 'scripts', 'e2e-cleanup-test-data.sh');

  try {
    if (process.env.E2E_DB_CLEANUP !== '1') {
      console.log(
        '[e2e] E2E_DB_CLEANUP=1 が未設定のため、DBの後片付けをスキップします' +
          `(手動で確認する場合: ${script})`
      );
    } else {
      console.log('[e2e] 全スキーマからE2Eテストデータを削除します');
      execFileSync(script, ['--yes'], { cwd: repoRoot, stdio: 'inherit', timeout: 300_000 });
    }
  } finally {
    releaseAcceptanceTestLock();
  }
}
