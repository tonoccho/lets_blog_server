/**
 * issue #1771: apiClient が投げる `APIエラー (<status>): <本文>` から、本文の `details.reason` だけを取り出す。
 * 文言の照合はしない。既知の原因以外は null。
 */
import { getApiErrorReason, GITHUB_AUTH_FAILED, GITHUB_TOKEN_NOT_CONFIGURED } from '../apiErrorReason';

const err = (status: number, body: string) => new Error(`APIエラー (${status}): ${body}`);

describe('getApiErrorReason', () => {
  it('トークン未設定の原因を取り出す', () => {
    const e = err(409, '{"error":"x","details":{"reason":"GITHUB_TOKEN_NOT_CONFIGURED"}}');
    expect(getApiErrorReason(e)).toBe(GITHUB_TOKEN_NOT_CONFIGURED);
  });

  it('認証失敗の原因を取り出す', () => {
    const e = err(502, '{"error":"x","details":{"reason":"GITHUB_AUTH_FAILED"}}');
    expect(getApiErrorReason(e)).toBe(GITHUB_AUTH_FAILED);
  });

  it('日本語の文言が同じでも reason が無ければ null(文言を照合しない)', () => {
    expect(getApiErrorReason(err(409, '{"error":"ユーザーの GitHub トークンが設定されていません"}'))).toBeNull();
  });

  it('未知の reason は null', () => {
    expect(getApiErrorReason(err(502, '{"error":"x","details":{"reason":"SOMETHING"}}'))).toBeNull();
  });

  it('details がオブジェクトでなければ null', () => {
    expect(getApiErrorReason(err(502, '{"error":"x","details":"GITHUB_AUTH_FAILED"}'))).toBeNull();
    expect(getApiErrorReason(err(502, '{"error":"x","details":null}'))).toBeNull();
  });

  it('本文が JSON でなければ null', () => {
    expect(getApiErrorReason(err(502, 'Bad Gateway'))).toBeNull();
  });

  it('本文が JSON のオブジェクトでなければ null', () => {
    expect(getApiErrorReason(err(502, '"GITHUB_AUTH_FAILED"'))).toBeNull();
    expect(getApiErrorReason(err(502, 'null'))).toBeNull();
  });

  it('APIエラー形式でない例外・Error以外は null', () => {
    expect(getApiErrorReason(new Error('boom'))).toBeNull();
    expect(getApiErrorReason('APIエラー (502): {"details":{"reason":"GITHUB_AUTH_FAILED"}}')).toBeNull();
    expect(getApiErrorReason(undefined)).toBeNull();
  });
});
