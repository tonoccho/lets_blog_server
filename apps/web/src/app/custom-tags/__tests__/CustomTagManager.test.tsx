import { act, render, screen, fireEvent } from '@testing-library/react'
import { buildPreviewSrcDoc, fetchPreview, SAMPLE_CONTENT, TemplateEditor } from '../CustomTagManager'

/**
 * カスタムタグ編集のライブプレビュー。サーバーへの問い合わせは外部との通信なので `fetch` の層で模す。
 * タイマー(300ms のデバウンス)は jest の偽タイマーで進める。自前のモジュールは差し替えない(#1834)。
 */
function previewResponse(html: string, css = '') {
  return { ok: true, status: 200, json: async () => ({ html, css }) } as unknown as Response
}

describe('buildPreviewSrcDoc', () => {
  it('{{attr:xxx}} をサンプル値へ置換し、CSS を style に入れた HTML 文書にする', () => {
    const doc = buildPreviewSrcDoc('<p>{{attr:color}} と {{attr:size_1}}</p>', '.a{}')

    expect(doc).toBe(
      '<!DOCTYPE html><html><head><meta charset="utf-8"><style>.a{}</style></head><body><p>サンプルcolor と サンプルsize_1</p></body></html>'
    )
  })
})

describe('fetchPreview', () => {
  afterEach(() => {
    jest.restoreAllMocks()
  })

  it('プロジェクトのプレビュー API へ POST し、結果をそのまま返す', async () => {
    const fetchMock = jest.fn().mockResolvedValue(previewResponse('<b>x</b>', '.b{}'))
    global.fetch = fetchMock as unknown as typeof fetch

    await expect(fetchPreview(7, '<b>{{content}}</b>', '.b{}', 'x')).resolves.toEqual({ html: '<b>x</b>', css: '.b{}' })

    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/projects/7/custom-tags/preview')
    expect(init.method).toBe('POST')
    expect(JSON.parse(init.body)).toEqual({ htmlTemplate: '<b>{{content}}</b>', cssContent: '.b{}', testContent: 'x' })
  })

  it('失敗の応答に error があればその文言で例外にする', async () => {
    global.fetch = jest.fn().mockResolvedValue({
      ok: false,
      status: 400,
      json: async () => ({ error: 'テンプレートが不正です' }),
    }) as unknown as typeof fetch

    await expect(fetchPreview(7, 'h', 'c', 't')).rejects.toThrow('テンプレートが不正です')
  })

  it('失敗の応答に error が無い・本文が JSON でないときはステータスつきの既定文言にする', async () => {
    global.fetch = jest.fn().mockResolvedValue({
      ok: false,
      status: 502,
      json: async () => {
        throw new Error('not json')
      },
    }) as unknown as typeof fetch
    await expect(fetchPreview(7, 'h', 'c', 't')).rejects.toThrow('プレビューの取得に失敗しました (502)')

    global.fetch = jest.fn().mockResolvedValue({
      ok: false,
      status: 500,
      json: async () => ({}),
    }) as unknown as typeof fetch
    await expect(fetchPreview(7, 'h', 'c', 't')).rejects.toThrow('プレビューの取得に失敗しました (500)')
  })
})

describe('TemplateEditor', () => {
  let fetchMock: jest.Mock

  beforeEach(() => {
    jest.useFakeTimers()
    fetchMock = jest.fn().mockResolvedValue(previewResponse('<p>{{attr:k}}</p>', '.p{}'))
    global.fetch = fetchMock as unknown as typeof fetch
  })

  afterEach(() => {
    jest.useRealTimers()
  })

  async function flush(ms = 300) {
    await act(async () => {
      jest.advanceTimersByTime(ms)
    })
  }

  it('初期値を入力欄に入れ、300ms 後にプレビューを取得して iframe に反映する', async () => {
    render(<TemplateEditor projectId={7} initialHtml="<p>{{content}}</p>" initialCss=".p{}" />)

    expect((screen.getByLabelText(/HTMLテンプレート/) as HTMLTextAreaElement).value).toBe('<p>{{content}}</p>')
    expect((screen.getByLabelText(/^CSS/) as HTMLTextAreaElement).value).toBe('.p{}')
    expect(fetchMock).not.toHaveBeenCalled()

    await flush()

    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({
      htmlTemplate: '<p>{{content}}</p>',
      cssContent: '.p{}',
      testContent: SAMPLE_CONTENT,
    })
    const frame = screen.getByTitle('カスタムタグプレビュー') as HTMLIFrameElement
    expect(frame.getAttribute('srcdoc')).toContain('<p>サンプルk</p>')
  })

  it('入力が続く間はデバウンスし、止まってから 1 回だけ問い合わせる', async () => {
    render(<TemplateEditor projectId={7} initialHtml="a" initialCss="" />)

    fireEvent.change(screen.getByLabelText(/HTMLテンプレート/), { target: { value: 'ab' } })
    await flush(200)
    fireEvent.change(screen.getByLabelText(/^CSS/), { target: { value: '.x{}' } })
    await flush(200)
    fireEvent.change(screen.getByPlaceholderText(SAMPLE_CONTENT), { target: { value: 'テスト本文' } })
    expect(fetchMock).not.toHaveBeenCalled()

    await flush(300)

    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({
      htmlTemplate: 'ab',
      cssContent: '.x{}',
      testContent: 'テスト本文',
    })
  })

  it('プレビューの取得に失敗したらエラーを表示し、次に成功すると消す', async () => {
    fetchMock.mockResolvedValueOnce({ ok: false, status: 400, json: async () => ({ error: '不正なテンプレート' }) })
    render(<TemplateEditor projectId={7} initialHtml="a" initialCss="" />)
    await flush()
    expect(screen.getByText('不正なテンプレート')).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText(/HTMLテンプレート/), { target: { value: 'b' } })
    await flush()

    expect(screen.queryByText('不正なテンプレート')).not.toBeInTheDocument()
  })

  it('Error 以外の値で失敗しても、文字列にしてエラー表示にする', async () => {
    fetchMock.mockRejectedValueOnce('通信できません')
    render(<TemplateEditor projectId={7} initialHtml="a" initialCss="" />)

    await flush()

    expect(screen.getByText('通信できません')).toBeInTheDocument()
  })

  it('取り消された問い合わせの結果は反映しない(応答前に再入力・アンマウント)', async () => {
    let resolveFirst: (r: Response) => void = () => {}
    fetchMock.mockImplementationOnce(() => new Promise<Response>((r) => (resolveFirst = r)))
    const { unmount } = render(<TemplateEditor projectId={7} initialHtml="a" initialCss="" />)
    await flush()
    expect(fetchMock).toHaveBeenCalledTimes(1)

    unmount()
    await act(async () => {
      resolveFirst(previewResponse('<i>late</i>'))
    })

    // アンマウント後に反映しても例外にならず、何も起きない
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('取り消された問い合わせの失敗も反映しない', async () => {
    let rejectFirst: (e: unknown) => void = () => {}
    fetchMock.mockImplementationOnce(() => new Promise<Response>((_r, rej) => (rejectFirst = rej)))
    render(<TemplateEditor projectId={7} initialHtml="a" initialCss="" />)
    await flush()

    fireEvent.change(screen.getByLabelText(/HTMLテンプレート/), { target: { value: 'b' } })
    await act(async () => {
      rejectFirst(new Error('古い失敗'))
    })

    expect(screen.queryByText('古い失敗')).not.toBeInTheDocument()
  })
})
