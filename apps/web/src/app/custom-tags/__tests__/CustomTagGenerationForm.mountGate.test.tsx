import { renderToString } from 'react-dom/server'
import { useSession } from 'next-auth/react'

/**
 * issue #1414(#1413の横展開): ハイドレーション完了前は「生成」ボタンを押せないようにする。
 * マウント前の状態は、`react` の `useEffect` を差し替えて作らず、サーバー描画(`renderToString`。
 * effect は走らない)で作る。実際にサーバーが返す HTML と同じ状態を見る(#1834)。
 *
 * 自前の useCustomTagGeneration は差し替えない。`next-auth/react` はブラウザのセッション取得で、実行基盤の代替。
 */
jest.mock('server-only', () => ({}));
jest.mock('next/server', () => require('@/lib/__tests__/testUtils/serverRuntime').nextServerModule);
jest.mock('next/headers', () => require('@/lib/__tests__/testUtils/serverRuntime').nextHeadersModule);
jest.mock('next-auth/jwt', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthJwtModule);
jest.mock('next-auth', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthModule);
jest.mock('next/cache', () => ({ revalidatePath: jest.fn() }));
jest.mock('next/navigation', () => ({
  redirect: (path: string) => {
    throw new Error(`NEXT_REDIRECT:${path}`);
  },
}));
jest.mock('next-auth/react')

import { CustomTagGenerationForm } from '../CustomTagGenerationForm'

describe('CustomTagGenerationForm(マウント前)', () => {
  it('セッション解決済みでも、マウント前は生成ボタンを押せない(ネイティブPOSTへのフォールバックを塞ぐ、issue #1414)', () => {
    ;(useSession as jest.Mock).mockReturnValue({
      data: { user: { name: 'x' } },
      status: 'authenticated',
      update: jest.fn(),
    })

    const html = renderToString(<CustomTagGenerationForm projects={[]} currentProjectId={null} />)

    expect(html).toMatch(/<button[^>]*disabled=""[^>]*>生成<\/button>/)
  })
})
