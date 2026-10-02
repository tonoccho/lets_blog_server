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

/**
 * AI 応答の Markdown 描画(issue #1566)。
 * assistant は GFM として描画し、生 HTML は要素にしない。user はテキストのまま改行を保持する。
 */
describe('ArticlePlanChat の Markdown 描画 (issue #1566)', () => {
  const renderHistory = (history: { role: 'user' | 'assistant'; content: string }[]) =>
    render(<ArticlePlanChat history={history} onSend={jest.fn()} isLoading={false} />)

  it('### の見出しを h3 要素として描画する', () => {
    renderHistory([{ role: 'assistant', content: '### 小見出し' }])
    expect(screen.getByRole('heading', { level: 3, name: '小見出し' })).toBeInTheDocument()
  })

  it('見出し・リスト・強調・コードブロックを要素として描画し、記号を表示しない', () => {
    const { container } = renderHistory([
      {
        role: 'assistant',
        content: '## 方針\n\n- 項目A\n- 項目B\n\n1. 手順1\n\n**太字** と *斜体* と `inline`\n\n```\nconst a = 1\n```',
      },
    ])
    expect(screen.getByRole('heading', { level: 2, name: '方針' })).toBeInTheDocument()
    expect(container.querySelectorAll('ul li')).toHaveLength(2)
    expect(container.querySelectorAll('ol li')).toHaveLength(1)
    expect(container.querySelector('strong')?.textContent).toBe('AI:')
    expect(screen.getByText('太字').tagName).toBe('STRONG')
    expect(screen.getByText('斜体').tagName).toBe('EM')
    expect(screen.getByText('inline').tagName).toBe('CODE')
    expect(container.querySelector('pre code')?.textContent).toContain('const a = 1')
    expect(container.textContent).not.toContain('##')
    expect(container.textContent).not.toContain('**')
    expect(container.textContent).not.toContain('```')
  })

  it('GFM の表を table として描画し、横スクロール枠に入れ、ダーク用の文字色を持つ', () => {
    const { container } = renderHistory([
      { role: 'assistant', content: '| 列1 | 列2 |\n| --- | --- |\n| a | b |' },
    ])
    const table = container.querySelector('table')
    expect(table).not.toBeNull()
    expect(container.querySelectorAll('th')).toHaveLength(2)
    expect(container.querySelectorAll('td')).toHaveLength(2)
    expect(table!.parentElement!.className).toContain('overflow-x-auto')
    expect(container.querySelector('th')!.className).toContain('dark:')
    expect(container.querySelector('td')!.className).toContain('dark:')
  })

  it('コードブロックは横スクロールする', () => {
    const { container } = renderHistory([{ role: 'assistant', content: '```\nlong\n```' }])
    expect(container.querySelector('pre')!.className).toContain('overflow-x-auto')
  })

  it('生 HTML(script / onerror)は要素として挿入されず、文字として残る', () => {
    const { container } = renderHistory([
      {
        role: 'assistant',
        content: '前置き\n\n<script>window.__x=1</script>\n\n<img src=x onerror="window.__x=2">\n\n<b>raw</b>',
      },
    ])
    expect(container.querySelector('script')).toBeNull()
    expect(container.querySelector('img')).toBeNull()
    expect(container.querySelector('b')).toBeNull()
    expect(container.querySelector('[onerror]')).toBeNull()
  })

  it('リンクは別タブ・noopener noreferrer で開く', () => {
    renderHistory([{ role: 'assistant', content: '[公式](https://example.com/a)' }])
    const link = screen.getByRole('link', { name: '公式' })
    expect(link).toHaveAttribute('href', 'https://example.com/a')
    expect(link).toHaveAttribute('target', '_blank')
    expect(link).toHaveAttribute('rel', 'noopener noreferrer')
  })

  it.each(['javascript:alert(1)', 'data:text/html,x', 'vbscript:x'])(
    '危険なスキーム %s のリンクは href を持たない',
    (url) => {
      const { container } = renderHistory([{ role: 'assistant', content: `[悪い](${url})` }])
      expect(container.querySelector('a[href^="javascript"], a[href^="data"], a[href^="vbscript"]')).toBeNull()
      expect(container.textContent).toContain('悪い')
    },
  )

  it('mailto と相対リンクは許可される', () => {
    renderHistory([{ role: 'assistant', content: '[m](mailto:a@example.com) [r](/projects/1)' }])
    expect(screen.getByRole('link', { name: 'm' })).toHaveAttribute('href', 'mailto:a@example.com')
    expect(screen.getByRole('link', { name: 'r' })).toHaveAttribute('href', '/projects/1')
  })

  it('ユーザーの発言は Markdown として解釈せず、改行を保持する', () => {
    const { container } = renderHistory([{ role: 'user', content: '## 見出し\n**そのまま**\n- 行' }])
    expect(container.querySelector('.bg-blue-100 :is(h1, h2, ul, em)')).toBeNull()
    expect(container.textContent).toContain('## 見出し')
    expect(container.textContent).toContain('**そのまま**')
    const text = screen.getByText(/見出し/, { selector: 'span' })
    expect(text.className).toContain('whitespace-pre-wrap')
    expect(text.textContent).toContain('\n')
  })

  it('ラベルは strong で「AI:」「あなた:」の構造を保つ', () => {
    const { container } = renderHistory([
      { role: 'user', content: 'q' },
      { role: 'assistant', content: 'a' },
    ])
    const labels = Array.from(container.querySelectorAll('strong')).map((e) => e.textContent)
    expect(labels).toEqual(['あなた:', 'AI:'])
  })
})
