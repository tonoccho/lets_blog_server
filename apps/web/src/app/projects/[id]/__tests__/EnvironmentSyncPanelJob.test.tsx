import { render, screen } from '@testing-library/react'
import type { Project, Site } from '@/lib/apiClient'

type State = { error?: string; success?: boolean; jobId?: number }
let mockState: State = {}
let mockPending = false
jest.mock('../actions', () => ({ syncEnvironmentAction: jest.fn() }))
jest.mock('react', () => ({
  ...jest.requireActual('react'),
  useActionState: () => [mockState, jest.fn(), mockPending],
}))

import { EnvironmentSyncPanel } from '../EnvironmentSyncPanel'

function site(id: number): Site {
  return {
    id, name: 's', siteKey: `s${id}`, cmsType: 'WORDPRESS', baseUrl: 'https://example.com',
    createdAt: '', updatedAt: '', connectionCheckStatus: null, managedWordpress: true, sshConfigured: false,
  } as Site
}

const project = {
  id: 1, name: 'p', slug: 'p', localSite: site(10), testSite: site(20), productionSite: site(30),
  masterEnvironment: 'test', githubRepository: null, createdAt: '', updatedAt: '',
} as unknown as Project

beforeEach(() => {
  mockState = {}
  mockPending = false
})

describe('EnvironmentSyncPanel(issue #1697: ジョブ受理の非同期)', () => {
  it('受理されたら、処理キューに追加された旨を示し、同期が終わったとは示さない', () => {
    mockState = { success: true, jobId: 3 }
    render(<EnvironmentSyncPanel projectId={1} project={project} />)
    expect(screen.getByText(/同期を要求しました。処理キューに追加されました/)).toBeInTheDocument()
    expect(screen.queryByText('同期しました。')).toBeNull()
  })

  it('受理後も同期ボタンは押せる状態のままで、長時間の待機表示にならない', () => {
    mockState = { success: true, jobId: 3 }
    render(<EnvironmentSyncPanel projectId={1} project={project} />)
    expect(screen.getByRole('button', { name: '同期する' })).toBeEnabled()
    expect(screen.queryByText(/数分かかる場合があります/)).toBeNull()
  })

  it('エラーは赤字で示し、受理の表示は出さない', () => {
    mockState = { error: '失敗しました' }
    render(<EnvironmentSyncPanel projectId={1} project={project} />)
    expect(screen.getByText('失敗しました')).toBeInTheDocument()
    expect(screen.queryByText(/同期を要求しました/)).toBeNull()
  })

  it('受理の往復の間だけ送信ボタンを無効にする', () => {
    mockPending = true
    render(<EnvironmentSyncPanel projectId={1} project={project} />)
    expect(screen.getByRole('button', { name: /要求中/ })).toBeDisabled()
  })
})
