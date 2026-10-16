/**
 * @jest-environment node
 */
import { renderToStaticMarkup } from 'react-dom/server'
import type { ReactElement } from 'react'

jest.mock('server-only', () => ({}))
// 自前の @/lib/session・@/lib/auth・子コンポーネント(SideNav / InfoRail / Footer など)は差し替えない。
// 差し替えるのは Next.js の実行基盤(next/font・next/navigation・CSS・next-auth のセッション取得)だけ(#1832)。
jest.mock('next/font/google', () => ({
  Geist: () => ({ variable: 'geist' }),
  Geist_Mono: () => ({ variable: 'geist-mono' }),
}))
// useServerInsertedHTML はコールバックを集め、Next が </head> 直前へ差し込むものを再現する。
const insertedHtml: Array<() => React.ReactNode> = []
jest.mock('next/navigation', () => ({
  useServerInsertedHTML: (cb: () => React.ReactNode) => {
    insertedHtml.push(cb)
  },
  usePathname: () => '/',
}))
jest.mock('next-auth', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthModule)
jest.mock('../globals.css', () => ({}), { virtual: true })

import { getServerSessionMock } from '@/lib/__tests__/testUtils/serverRuntime'
import RootLayout from '../layout'

async function render(session: unknown): Promise<string> {
  getServerSessionMock.mockResolvedValue(session)
  insertedHtml.length = 0
  const element = (await RootLayout({ children: <p>child</p> })) as ReactElement
  return renderToStaticMarkup(element)
}

/** 差し込まれる HTML(<script>)を文字列にする。 */
function insertedMarkup(): string {
  return renderToStaticMarkup(<>{insertedHtml.map((cb) => cb())}</>)
}

describe('RootLayout theme script placement (#1238)', () => {
  it('hands the theme script to the HTML stream and renders no <script> in the React tree', async () => {
    const html = await render(null)
    expect(insertedHtml).toHaveLength(1)
    expect(insertedMarkup()).toContain('localStorage.getItem("theme")')
    expect(html).not.toContain('<script')
    expect(html).toContain('child')
    expect(html).not.toContain('<nav')
  })

  it('shows the nav for a regular user', async () => {
    const html = await render({ user: { role: 'user', email: 'u@example.com' } })
    expect(html).toContain('u@example.com')
    expect(html).toContain('<nav')
  })

  it('adds admin nav items for an admin', async () => {
    const user = await render({ user: { role: 'user', email: 'a@example.com' } })
    const admin = await render({ user: { role: 'admin', email: 'a@example.com' } })
    const count = (h: string) => (h.match(/<a /g) ?? []).length
    expect(count(admin)).toBeGreaterThan(count(user))
    expect(admin).toContain('data-testid="side-nav-admin-label"')
    expect(user).not.toContain('data-testid="side-nav-admin-label"')
  })
})
