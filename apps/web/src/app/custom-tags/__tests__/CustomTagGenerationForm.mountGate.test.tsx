import { render, screen } from '@testing-library/react'
import { useSession } from 'next-auth/react'

/**
 * issue #1414(#1413の横展開): ハイドレーション完了前は「生成」ボタンを押せないようにする。
 * 理由と単独ファイルにする事情は SshKeyPairsPanel.mountGate.test.tsx を参照
 * (useEffect を no-op にして「マウント前」を再現するため、他のテストと分離する)。
 */
jest.mock('react', () => ({
  __esModule: true,
  ...jest.requireActual('react'),
  useEffect: jest.fn(),
}))

jest.mock('next-auth/react')
jest.mock('@/lib/useCustomTagGeneration', () => ({
  useCustomTagGeneration: () => ({
    isLoading: false,
    error: null,
    result: null,
    generate: jest.fn(),
    reset: jest.fn(),
  }),
}))
jest.mock('@/lib/useCustomTagValidation', () => ({
  useCustomTagValidation: () => ({
    isLoading: false,
    error: null,
    result: null,
    validate: jest.fn(),
    reset: jest.fn(),
  }),
}))
jest.mock('../ValidationPanel', () => ({
  ValidationPanel: () => <div>ValidationPanel</div>,
}))

import { CustomTagGenerationForm } from '../CustomTagGenerationForm'

describe('CustomTagGenerationForm(マウント前)', () => {
  it('セッション解決済みでも、マウント前は生成ボタンを押せない(ネイティブPOSTへのフォールバックを塞ぐ、issue #1414)', () => {
    ;(useSession as jest.Mock).mockReturnValue({
      data: { user: { name: 'x' } },
      status: 'authenticated',
      update: jest.fn(),
    })

    render(<CustomTagGenerationForm projects={[]} currentProjectId={null} onGenerationSuccess={jest.fn()} />)

    expect(screen.getByRole('button', { name: '生成' })).toBeDisabled()
  })
})
