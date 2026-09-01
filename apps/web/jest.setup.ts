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
