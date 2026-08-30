import { render, screen, act } from '@testing-library/react'
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

  /**
   * 他タブで言語が切り替わったときに storage イベントで追随する。
   * 同一タブ内の切り替えは LanguageSwitcher が `window.location.reload()` するため対象外。
   */
  it('他タブでの変更(storageイベント)に追随する', () => {
    render(
      <I18nProvider initialLocale="ja">
        <Probe />
      </I18nProvider>
    )
    expect(screen.getByTestId('locale')).toHaveTextContent('ja')

    act(() => {
      window.localStorage.setItem('locale', 'en')
      window.dispatchEvent(new StorageEvent('storage', { key: 'locale', newValue: 'en' }))
    })

    expect(screen.getByTestId('locale')).toHaveTextContent('en')
  })
})
