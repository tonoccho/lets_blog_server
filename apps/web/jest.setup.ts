import '@testing-library/jest-dom'

// jsdomはResizeObserverを実装していないため、rechartsのResponsiveContainer等が
// 参照してもクラッシュしないよう最小限のno-opを補う(issue #426)。
class ResizeObserverMock {
  observe() {}
  unobserve() {}
  disconnect() {}
}

if (typeof globalThis.ResizeObserver === 'undefined') {
  globalThis.ResizeObserver = ResizeObserverMock as unknown as typeof ResizeObserver
}

// jsdom は TextEncoder / TextDecoder を実装していない。jose(JWT のデコード。@/lib/auth が読む)は
// import の時点でこれを参照するため、jsdom のテストで jose を差し替えずに済むよう Node のものを補う(#1832)。
if (typeof globalThis.TextEncoder === 'undefined') {
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  const { TextEncoder, TextDecoder } = require('util')
  globalThis.TextEncoder = TextEncoder
  globalThis.TextDecoder = TextDecoder
}
