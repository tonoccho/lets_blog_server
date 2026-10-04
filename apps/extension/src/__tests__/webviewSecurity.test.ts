import { buildRealSitePreviewCsp, buildScriptedCsp, createNonce } from '../webviewSecurity';

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
