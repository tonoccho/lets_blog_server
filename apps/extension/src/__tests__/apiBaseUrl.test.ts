import { gatewayBaseUrl, gatewayUrl } from '../apiBaseUrl';
import { resetMocks, setConfiguration } from '../__mocks__/vscode';

beforeEach(() => {
  resetMocks();
});

describe('gatewayBaseUrl', () => {
  it('letsBlog.serverUrlの設定値を返す', () => {
    setConfiguration('letsBlog.serverUrl', 'https://blog.example.test');
    expect(gatewayBaseUrl()).toBe('https://blog.example.test');
  });

  it('未設定ならローカル既定値を返す', () => {
    expect(gatewayBaseUrl()).toBe('https://localhost');
  });

  it('末尾のスラッシュを除去する(パス連結で//にならないようにするため)', () => {
    setConfiguration('letsBlog.serverUrl', 'https://blog.example.test///');
    expect(gatewayBaseUrl()).toBe('https://blog.example.test');
  });
});

describe('gatewayUrl', () => {
  it('/apiから始まるパスをgateway宛の絶対URLへ変換する', () => {
    setConfiguration('letsBlog.serverUrl', 'https://blog.example.test');
    expect(gatewayUrl('/api/sites')).toBe('https://blog.example.test/api/sites');
  });

  it('先頭スラッシュが無いパスでも区切りを補う', () => {
    setConfiguration('letsBlog.serverUrl', 'https://blog.example.test');
    expect(gatewayUrl('api/sites')).toBe('https://blog.example.test/api/sites');
  });

  it('クエリ文字列はそのまま連結する', () => {
    setConfiguration('letsBlog.serverUrl', 'https://blog.example.test');
    expect(gatewayUrl('/api/diagrams?projectId=3')).toBe(
      'https://blog.example.test/api/diagrams?projectId=3'
    );
  });

  it('末尾スラッシュ付きのベースURLでもパス区切りが重複しない', () => {
    setConfiguration('letsBlog.serverUrl', 'https://blog.example.test/');
    expect(gatewayUrl('/api/sites')).toBe('https://blog.example.test/api/sites');
  });
});
