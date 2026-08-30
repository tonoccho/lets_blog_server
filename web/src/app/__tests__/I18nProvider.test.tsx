import { render, screen, act } from '@testing-library/react'
import { renderToString } from 'react-dom/server'
import { hydrateRoot } from 'react-dom/client'
import { I18nProvider, useI18n } from '../I18nProvider'

/**
 * issue #721: `useEffect` 内での同期的な `setLocale` を `useSyncExternalStore` へ置き換えた際に、
 * 言語の反映と localStorage による永続化の挙動が変わっていないことを固定する。
 */
function Probe() {
  const { locale, t } = useI18n()
  return (
    <div>
      <span data-testid="locale">{locale}</span>
      <span data-testid="message">{t('common', 'appName')}</span>
    </div>
  )
}

describe('I18nProvider (issue #721)', () => {
  afterEach(() => {
    window.localStorage.clear()
  })

  it('localStorageに保存が無ければinitialLocaleを使う', () => {
    render(
      <I18nProvider>
        <Probe />
      </I18nProvider>
    )
    expect(screen.getByTestId('locale')).toHaveTextContent('ja')
  })

  it('initialLocaleを明示すればそれを使う', () => {
    render(
      <I18nProvider initialLocale="en">
        <Probe />
      </I18nProvider>
    )
    expect(screen.getByTestId('locale')).toHaveTextContent('en')
  })

  it('localStorageに保存された言語が initialLocale より優先される(永続化)', () => {
    window.localStorage.setItem('locale', 'en')

    render(
      <I18nProvider initialLocale="ja">
        <Probe />
      </I18nProvider>
    )
    expect(screen.getByTestId('locale')).toHaveTextContent('en')
  })

  it('未知の言語コードが保存されていても無視してinitialLocaleへ倒す', () => {
    window.localStorage.setItem('locale', 'zz')

    render(
      <I18nProvider initialLocale="ja">
        <Probe />
      </I18nProvider>
    )
    expect(screen.getByTestId('locale')).toHaveTextContent('ja')
  })

  it('選択した言語のメッセージを引ける', () => {
    window.localStorage.setItem('locale', 'en')

    render(
      <I18nProvider>
        <Probe />
      </I18nProvider>
    )
    expect(screen.getByTestId('message')).toHaveTextContent(
      "Let's Blog Server Admin Dashboard"
    )
  })

  it('localStorageが読めない環境でもinitialLocaleへ倒す', () => {
    const spy = jest
      .spyOn(Storage.prototype, 'getItem')
      .mockImplementation(() => {
        throw new Error('SecurityError')
      })

    render(
      <I18nProvider initialLocale="ja">
        <Probe />
      </I18nProvider>
    )
    expect(screen.getByTestId('locale')).toHaveTextContent('ja')

    spy.mockRestore()
  })

  /**
   * この変更で唯一リグレッションが起きうるのがハイドレーション経路なので、SSR された HTML へ
   * 実際に hydrate して確認する。`useSyncExternalStore` の `getServerSnapshot` はハイドレーション時
   * にも使われるため、保存値があっても**初回は initialLocale で描画され**、直後に保存値へ
   * 切り替わる。変更前(`useEffect` で切り替える実装)と同じ順序であり、ハイドレーション不一致の
   * 警告も出ない。`useState` の遅延初期化にするとここが初回から `en` になり不一致になる。
   */
  it('SSR済みHTMLへのハイドレーションで不一致を起こさず、直後に保存値へ切り替わる', () => {
    const html = renderToString(
      <I18nProvider initialLocale="ja">
        <Probe />
      </I18nProvider>
    )
    expect(html).toContain('>ja<')

    window.localStorage.setItem('locale', 'en')

    const container = document.createElement('div')
    container.innerHTML = html
    document.body.appendChild(container)

    const errorSpy = jest.spyOn(console, 'error').mockImplementation(() => {})

    act(() => {
      hydrateRoot(
        container,
        <I18nProvider initialLocale="ja">
          <Probe />
        </I18nProvider>
      )
    })

    // ハイドレーション不一致の警告が出ていないこと。
    expect(errorSpy).not.toHaveBeenCalled()
    // passive effect でクライアントのスナップショットへ切り替わっていること。
    expect(container.querySelector('[data-testid="locale"]')?.textContent).toBe('en')

    errorSpy.mockRestore()
    document.body.removeChild(container)
  })
})
