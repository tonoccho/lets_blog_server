import { gatewayBaseUrl, gatewayUrl } from '@/lib/apiBaseUrl';

/**
 * ベースURLの組み立てを1箇所へ集約したモジュール(issue #584)の検証。
 * apiClient.ts / proxy.ts の双方がここだけを使うため、ここの挙動がWebの全API呼び出し先を決める。
 */
describe('apiBaseUrl', () => {
  const original = process.env.LETS_BLOG_GATEWAY_URL;

  afterEach(() => {
    if (original === undefined) {
      delete process.env.LETS_BLOG_GATEWAY_URL;
    } else {
      process.env.LETS_BLOG_GATEWAY_URL = original;
    }
  });

  describe('gatewayBaseUrl', () => {
    it('LETS_BLOG_GATEWAY_URLをそのまま使う', () => {
      process.env.LETS_BLOG_GATEWAY_URL = 'http://gateway:8080';
      expect(gatewayBaseUrl()).toBe('http://gateway:8080');
    });

    it('末尾のスラッシュを除去する', () => {
      process.env.LETS_BLOG_GATEWAY_URL = 'http://gateway:8080///';
      expect(gatewayBaseUrl()).toBe('http://gateway:8080');
    });

    it('未設定ならnginx経由の公開URLへフォールバックする', () => {
      delete process.env.LETS_BLOG_GATEWAY_URL;
      expect(gatewayBaseUrl()).toBe('https://localhost');
    });

    it('毎回環境変数を読み直す(モジュール読み込み時に固定しない)', () => {
      process.env.LETS_BLOG_GATEWAY_URL = 'http://gateway:8080';
      expect(gatewayBaseUrl()).toBe('http://gateway:8080');
      process.env.LETS_BLOG_GATEWAY_URL = 'https://localhost';
      expect(gatewayBaseUrl()).toBe('https://localhost');
    });
  });

  describe('gatewayUrl', () => {
    it('ベースURLとパスを連結する', () => {
      process.env.LETS_BLOG_GATEWAY_URL = 'http://gateway:8080';
      expect(gatewayUrl('/api/sites')).toBe('http://gateway:8080/api/sites');
    });

    it('クエリ文字列を保持する', () => {
      process.env.LETS_BLOG_GATEWAY_URL = 'http://gateway:8080';
      expect(gatewayUrl('/api/posts?page=2&size=20')).toBe('http://gateway:8080/api/posts?page=2&size=20');
    });

    it('先頭スラッシュが無いパスでもスラッシュを補う', () => {
      process.env.LETS_BLOG_GATEWAY_URL = 'http://gateway:8080';
      expect(gatewayUrl('api/sites')).toBe('http://gateway:8080/api/sites');
    });

    it('ベースURLの末尾スラッシュとパスの先頭スラッシュが二重にならない', () => {
      process.env.LETS_BLOG_GATEWAY_URL = 'http://gateway:8080/';
      expect(gatewayUrl('/api/sites')).toBe('http://gateway:8080/api/sites');
    });
  });
});
