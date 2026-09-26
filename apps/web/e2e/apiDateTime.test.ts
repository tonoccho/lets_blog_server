/**
 * @jest-environment node
 *
 * issue #1257: API の日時はオフセット無しのISO文字列(LocalDateTime.toString())で、
 * バックエンドはこれをUTCの壁時計値として扱う(#1314)。`Date.parse` はゾーン無し文字列を
 * 実行ホストのローカルTZで解釈するため、ホストがUTCでないと12時間ずれる。
 * ステップ定義は `parseApiDateTime` 経由でUTCとして解釈する。
 *
 * `apps/web/e2e/**` は通常 jest の対象外(#994)。helpers.test.ts と同じく、
 * 対象除外設定と testMatch を CLI 引数で上書きした jest 呼び出しで走らせる
 * (手順は e2e/helpers.test.ts の冒頭コメントを参照。実行時は TZ=Pacific/Auckland を付ける)。
 */
import { parseApiDateTime } from './support/apiDateTime';

describe('parseApiDateTime', () => {
  const expected = Date.UTC(2026, 8, 15, 21, 33, 54);

  it('ゾーン無しの文字列をUTCとして解釈する', () => {
    expect(parseApiDateTime('2026-09-15T21:33:54')).toBe(expected);
  });

  it('小数秒付きのゾーン無し文字列もUTCとして解釈する', () => {
    expect(parseApiDateTime('2026-09-15T21:33:54.123456')).toBe(expected + 123);
  });

  it('Z 付きはそのまま解釈する(二重に付けない)', () => {
    expect(parseApiDateTime('2026-09-15T21:33:54Z')).toBe(expected);
  });

  it('オフセット付きはそのオフセットで解釈する', () => {
    expect(parseApiDateTime('2026-09-16T09:33:54+12:00')).toBe(expected);
    expect(parseApiDateTime('2026-09-15T16:33:54-05:00')).toBe(expected);
  });

  it('解釈できない文字列は NaN', () => {
    expect(Number.isNaN(parseApiDateTime('not a date'))).toBe(true);
  });
});
