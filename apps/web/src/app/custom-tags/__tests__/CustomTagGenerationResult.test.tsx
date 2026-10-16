import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { CustomTagGenerationResult } from '../CustomTagGenerationResult'

/**
 * 自前の useCustomTagValidation・ValidationPanel・Server Action(@/app/custom-tags/actions、
 * @/app/projects/[id]/custom-tags/actions)・@/lib/session・@/lib/apiClient は差し替えない。差し替えるのは
 * Next.js の実行基盤と外部との通信(fetch)だけ(#1834)。
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

import { installFetch, jsonResponse, resetServerRuntime, textResponse } from '@/lib/__tests__/testUtils/serverRuntime'
import { ADMIN_SESSION } from '@/lib/__tests__/testUtils/webAdminRuntime'

const VALIDATE = 'POST /api/custom-tags/validate'
const SAVE = 'POST /api/custom-tags'
let fetchMock: jest.Mock
const bodies = (path: string) =>
  fetchMock.mock.calls
    .filter(([url, init]) => String(url).endsWith(path) && init?.method === 'POST')
    .map(([, init]) => JSON.parse(String(init.body)))

const RESULT = {
  jobId: 21,
  tagName: 'blue-button',
  description: '青いボタン',
  projectId: 7,
  htmlTemplate: '<button>{{content}}</button>',
  cssContent: '.blue-button { color: blue; }',
}

/**
 * issue #1409: 処理キューの「結果を見る」の遷移先。生成結果(ジョブの結果にだけある)を確認し、
 * 「保存」を押したときに初めて既存の保存先(カスタムタグ)へ登録する。
 */
describe('CustomTagGenerationResult (#1409)', () => {
  beforeEach(() => {
    resetServerRuntime(ADMIN_SESSION)
    fetchMock = installFetch({
      [VALIDATE]: () => jsonResponse({ isValid: true, errors: [], warnings: [] }),
      [SAVE]: () => jsonResponse({ id: 1 }, 201),
    })
  })

  it('shows the generated tag as not yet saved, with its HTML and CSS', () => {
    render(<CustomTagGenerationResult projectId={7} result={RESULT} />)

    const panel = screen.getByTestId('custom-tag-generation-result')
    expect(panel).toHaveAttribute('data-job-id', '21')
    expect(panel).toHaveTextContent('保存されていません')
    expect(panel).toHaveTextContent('[blue-button]')
    expect(panel).toHaveTextContent('青いボタン')
    expect(panel).toHaveTextContent('<button>{{content}}</button>')
    expect(panel).toHaveTextContent('.blue-button { color: blue; }')
    expect(screen.queryByText(/保存済みです/)).not.toBeInTheDocument()
  })

  it('validates the generated content once on display and shows the validation outcome', async () => {
    render(<CustomTagGenerationResult projectId={7} result={RESULT} />)

    expect(await screen.findByText('✓ 検証成功')).toBeInTheDocument()
    expect(bodies('/api/custom-tags/validate')).toEqual([
      { htmlTemplate: '<button>{{content}}</button>', cssContent: '.blue-button { color: blue; }' },
    ])
  })

  it('does not crash when the validation request itself fails, and shows the reason', async () => {
    fetchMock = installFetch({ [VALIDATE]: () => textResponse('down', 503) })
    render(<CustomTagGenerationResult projectId={7} result={RESULT} />)

    expect(await screen.findByText('APIエラー (503): down')).toBeInTheDocument()
    expect(screen.getByTestId('custom-tag-generation-result')).toBeInTheDocument()
  })

  it('shows "(なし)" for a missing description and hides the CSS section for an empty CSS', async () => {
    render(<CustomTagGenerationResult projectId={7} result={{ ...RESULT, description: null, cssContent: '' }} />)

    expect(screen.getByText('(なし)')).toBeInTheDocument()
    expect(screen.queryByText('CSS:')).not.toBeInTheDocument()
    await waitFor(() =>
      expect(bodies('/api/custom-tags/validate')).toEqual([{ htmlTemplate: '<button>{{content}}</button>', cssContent: '' }])
    )
  })

  it('explains the selector prefix that the bundled CSS will get, using the first selector', () => {
    render(<CustomTagGenerationResult projectId={7} result={RESULT} effectivePrefix="myproject" />)

    expect(screen.getAllByText(/myproject/, { selector: 'code' })).toHaveLength(2)
    expect(screen.getByText(/\.myproject \.blue-button/)).toBeInTheDocument()
  })

  it('falls back to "..." when the first selector cannot be taken out of the CSS', () => {
    render(<CustomTagGenerationResult projectId={7} result={{ ...RESULT, cssContent: ' { color: blue; }' }} effectivePrefix="myproject" />)

    expect(screen.getByText(/myproject \.\.\./)).toBeInTheDocument()
  })

  it('"保存" registers the tag through the existing tag save action for the project, then says it is saved', async () => {
    render(<CustomTagGenerationResult projectId={7} result={RESULT} />)

    fireEvent.click(screen.getByRole('button', { name: '保存' }))

    await waitFor(() => expect(screen.getByText('保存しました。')).toBeInTheDocument())
    expect(bodies('/api/custom-tags')).toEqual([
      {
        tagName: 'blue-button',
        description: '青いボタン',
        htmlTemplate: '<button>{{content}}</button>',
        cssContent: '.blue-button { color: blue; }',
        tagFormat: 'BLOCK',
        projectId: 7,
      },
    ])
    // 二重に保存させない
    expect(screen.queryByRole('button', { name: '保存' })).not.toBeInTheDocument()
  })

  it('saves without a description and a CSS when the generation had none', async () => {
    render(<CustomTagGenerationResult projectId={7} result={{ ...RESULT, description: null, cssContent: '' }} />)

    fireEvent.click(screen.getByRole('button', { name: '保存' }))

    await waitFor(() => expect(bodies('/api/custom-tags')).toHaveLength(1))
    const [body] = bodies('/api/custom-tags')
    expect(body).not.toHaveProperty('description')
    expect(body).not.toHaveProperty('cssContent')
  })

  it('shows the reason when the save is refused (e.g. a tag with the same name exists) and keeps the button', async () => {
    fetchMock = installFetch({
      [VALIDATE]: () => jsonResponse({ isValid: true, errors: [], warnings: [] }),
      [SAVE]: () => textResponse('タグ名 blue-button は既に登録されています', 409),
    })
    render(<CustomTagGenerationResult projectId={7} result={RESULT} />)

    fireEvent.click(screen.getByRole('button', { name: '保存' }))

    await waitFor(() =>
      expect(screen.getByText('APIエラー (409): タグ名 blue-button は既に登録されています')).toBeInTheDocument()
    )
    expect(screen.getByRole('button', { name: '保存' })).toBeEnabled()
    expect(screen.queryByText('保存しました。')).not.toBeInTheDocument()
  })

  it('disables the button while saving', async () => {
    let finish: (r: Response) => void = () => {}
    fetchMock = installFetch({
      [VALIDATE]: () => jsonResponse({ isValid: true, errors: [], warnings: [] }),
      [SAVE]: () => new Promise<Response>((r) => (finish = r)),
    })
    render(<CustomTagGenerationResult projectId={7} result={RESULT} />)

    fireEvent.click(screen.getByRole('button', { name: '保存' }))

    expect(await screen.findByRole('button', { name: '保存中…' })).toBeDisabled()
    finish(jsonResponse({ id: 1 }, 201))
    await waitFor(() => expect(screen.getByText('保存しました。')).toBeInTheDocument())
  })
})
