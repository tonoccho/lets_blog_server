import { execFileSync } from 'node:child_process';
import path from 'node:path';

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
 */
export default async function globalTeardown(): Promise<void> {
  const repoRoot = path.resolve(__dirname, '..', '..');
  const script = path.join(repoRoot, 'scripts', 'e2e-cleanup-test-data.sh');

  if (process.env.E2E_DB_CLEANUP !== '1') {
    console.log(
      '[e2e] E2E_DB_CLEANUP=1 が未設定のため、DBの後片付けをスキップします' +
        `(手動で確認する場合: ${script})`
    );
    return;
  }

  console.log('[e2e] 全スキーマからE2Eテストデータを削除します');
  execFileSync(script, ['--yes'], { cwd: repoRoot, stdio: 'inherit', timeout: 300_000 });
}
