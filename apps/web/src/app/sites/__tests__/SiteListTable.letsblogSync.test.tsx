import { render, screen, within } from '@testing-library/react'
import { ReactNode } from 'react'
import { SiteListTable } from '../SiteListTable'
import type { Site } from '@/lib/apiClient'

jest.mock('@/lib/formatDate', () => ({
  formatDateTime: () => '2024-01-01 10:00',
}))
jest.mock('../DeleteSiteButton', () => ({ DeleteSiteButton: () => <div>Delete Button</div> }))
jest.mock('../CheckConnectionButton', () => ({ CheckConnectionButton: () => <button>Check Connection</button> }))
jest.mock('next/link', () => {
  const Link = ({ children, href }: { children: ReactNode; href: string }) => <a href={href}>{children}</a>
  Link.displayName = 'MockLink'
  return Link
})

function site(id: number, letsblogSync: Site['letsblogSync']): Site {
  return {
    id,
    siteKey: `site-${id}`,
    name: `Site ${id}`,
    baseUrl: `https://s${id}.example.com`,
    cmsType: 'WORDPRESS',
    managedWordpress: false,
    connectionCheckStatus: null,
    sshConfigured: false,
    createdAt: '2024-01-01T10:00:00Z',
    updatedAt: '2024-01-01T10:00:00Z',
    letsblogSync,
  }
}

describe('SiteListTable の letsblog 同期失敗の表示(issue #1558)', () => {
  const render3 = () =>
    render(
      <SiteListTable
        sites={[
          site(1, { status: 'FAILED', error: 'wp-cliが失敗しました', hash: null, syncedAt: '2026-10-04T00:00:00Z' }),
          site(2, { status: 'SYNCED', error: null, hash: 'abc', syncedAt: '2026-10-04T00:00:00Z' }),
          site(3, undefined),
        ]}
        projects={[]}
        isAdmin={true}
        timezone="UTC"
        adminPath="/wp-admin"
      />
    )

  it('同期に失敗したサイトに同期失敗を表示し、理由を添える', () => {
    render3()
    const row = screen.getByText('site-1').closest('tr') as HTMLElement
    const badge = within(row).getByTestId('letsblog-sync-failed')
    expect(badge).toHaveTextContent('同期失敗')
    expect(badge).toHaveAttribute('title', 'wp-cliが失敗しました')
  })

  it('同期済みのサイトと同期したことのないサイトには表示しない', () => {
    render3()
    expect(within(screen.getByText('site-2').closest('tr') as HTMLElement).queryByTestId('letsblog-sync-failed')).toBeNull()
    expect(within(screen.getByText('site-3').closest('tr') as HTMLElement).queryByTestId('letsblog-sync-failed')).toBeNull()
  })

  it('理由が無い失敗でも同期失敗を表示し、理由の添え書きは付けない', () => {
    render(
      <SiteListTable
        sites={[site(1, { status: 'FAILED', error: null, hash: null, syncedAt: null })]}
        projects={[]}
        isAdmin={true}
        timezone="UTC"
        adminPath="/wp-admin"
      />
    )
    const badge = screen.getByTestId('letsblog-sync-failed')
    expect(badge).toHaveTextContent('同期失敗')
    expect(badge).not.toHaveAttribute('title')
  })
})
