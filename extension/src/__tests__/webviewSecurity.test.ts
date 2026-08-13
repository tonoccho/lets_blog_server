import { buildPreviewCsp, buildScriptedCsp, createNonce } from '../webviewSecurity';

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
