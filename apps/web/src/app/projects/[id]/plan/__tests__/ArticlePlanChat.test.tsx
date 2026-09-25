import { render, screen } from '@testing-library/react'
import { ArticlePlanChat } from '../ArticlePlanChat'

/**
 * 送信中(disabled)入力欄の配色(issue #1121)。
 * 入力欄は文字色を自身で持たず祖先の dark:text-neutral-50 を継承するため、
 * disabled:bg-neutral-100 だけだとダークモードで白地に白文字になる。
 */
function channel(v: number): number {
  const c = v / 255
  return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4
}
function luminance(hex: string): number {
  const r = parseInt(hex.slice(0, 2), 16)
  const g = parseInt(hex.slice(2, 4), 16)
  const b = parseInt(hex.slice(4, 6), 16)
  return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
}
function contrast(a: string, b: string): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x)
  return (hi + 0.05) / (lo + 0.05)
}

describe('ArticlePlanChat 送信中の入力欄の配色 (issue #1121)', () => {
  function renderLoading(isLoading: boolean) {
    render(<ArticlePlanChat history={[]} onSend={jest.fn()} isLoading={isLoading} />)
    return screen.getByPlaceholderText('質問や企画案を入力...') as HTMLInputElement
  }
  const tokens = (el: Element) => (el.getAttribute('class') ?? '').split(/\s+/).filter(Boolean)

  it('送信中は disabled で、ライトの背景を保ったままダーク用の背景対を持つ', () => {
    const input = renderLoading(true)
    expect(input).toBeDisabled()
    expect(tokens(input)).toEqual(
      expect.arrayContaining(['disabled:bg-neutral-100', 'dark:disabled:bg-neutral-800']),
    )
  })

  it('継承する文字色(neutral-50)とダーク背景(neutral-800)のコントラスト比が4.5以上', () => {
    const input = renderLoading(true)
    expect(tokens(input)).toContain('dark:disabled:bg-neutral-800')
    expect(contrast('fafafa', '262626')).toBeGreaterThanOrEqual(4.5)
  })

  it('送信中でなければ disabled ではない', () => {
    expect(renderLoading(false)).toBeEnabled()
  })
})
