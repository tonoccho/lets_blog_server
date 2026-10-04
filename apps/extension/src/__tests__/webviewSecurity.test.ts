import { buildPreviewCsp, buildRealSitePreviewCsp, buildScriptedCsp, createNonce } from '../webviewSecurity';

describe('buildPreviewCsp', () => {
  it('nonce付きのscript-srcのみを許可し、他のnonce/リモートスクリプトは許可しない', () => {
    const nonce = createNonce();
    const csp = buildPreviewCsp(nonce);

    expect(csp).toContain(`script-src 'nonce-${nonce}'`);
    expect(csp).not.toContain('unsafe-inline\' script');
    expect(csp).not.toMatch(/script-src[^;]*'unsafe-inline'/);
  });

  it('テーマCSS/画像/Webフォントの読み込みを許可する', () => {
    const csp = buildPreviewCsp(createNonce());

    expect(csp).toContain('img-src data: https: http:');
    expect(csp).toContain('font-src data: https: http:');
    expect(csp).toContain("style-src 'unsafe-inline'");
  });

  it('nonceが異なれば異なるCSPを生成する(発行のたびにパネル固有の値になる)', () => {
    const cspA = buildPreviewCsp('nonce-a');
    const cspB = buildPreviewCsp('nonce-b');

    expect(cspA).not.toEqual(cspB);
  });
});

describe('createNonce', () => {
  it('毎回異なる値を返す', () => {
    expect(createNonce()).not.toEqual(createNonce());
  });
});

describe('buildScriptedCsp', () => {
  it('nonce付きのscript-srcを含む', () => {
    const nonce = createNonce();
    expect(buildScriptedCsp(nonce)).toContain(`script-src 'nonce-${nonce}'`);
  });
});

describe('buildRealSitePreviewCsp(issue #1562)', () => {
  it('frame-srcにはプレビューURLのオリジンだけを許可する', () => {
    const csp = buildRealSitePreviewCsp('n', 'https://blog.example.com:8443/path/x?letsblog_preview=tok#h');

    expect(csp).toContain('frame-src https://blog.example.com:8443;');
    expect(csp).not.toContain('letsblog_preview');
    expect(csp).not.toMatch(/frame-src[^;]*(\*|https:\s|http:\s)/);
  });

  it("既定は'none'で、nonce付きスクリプトだけを許可する", () => {
    const csp = buildRealSitePreviewCsp('abc', 'https://blog.example.com/');

    expect(csp).toContain("default-src 'none'");
    expect(csp).toContain("script-src 'nonce-abc'");
  });

  it('http/https以外のURLでは何も許可せず例外にする', () => {
    expect(() => buildRealSitePreviewCsp('n', 'javascript:alert(1)')).toThrow();
    expect(() => buildRealSitePreviewCsp('n', 'not a url')).toThrow();
  });
});
