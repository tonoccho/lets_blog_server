import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { I18nProvider } from '../I18nProvider'
import { LanguageSwitcher } from '../LanguageSwitcher'

/**
 * issue #800: locale の解決を I18nProvider へ一本化した際に、
 * 表示・切り替え・永続化の挙動が変わっていないことと、
 * 不正な保存値でも本文とセレクトの表示が一致することを固定する。
 */
function renderSwitcher() {
  return render(
    <I18nProvider>
      <LanguageSwitcher />
    </I18nProvider>
  )
}

describe('LanguageSwitcher (issue #800)', () => {
  let reloadSpy: jest.SpyInstance

  beforeEach(() => {
    // jsdomの window.location.reload は実際には遷移せず「Not implemented: navigation」を
    // コンソールに出す。自前の継ぎ目(pageReloader)は差し替えず、その出力で再読み込みの要求を観測する(#1832)。
    reloadSpy = jest.spyOn(console, 'error').mockImplementation(() => {})
  })

  afterEach(() => {
    window.localStorage.clear()
    reloadSpy.mockRestore()
  })

  it('保存が無ければ既定のjaを選択状態にする', () => {
    renderSwitcher()
    expect(screen.getByRole('combobox', { name: '言語選択' })).toHaveValue('ja')
  })

  it('保存済みのlocaleを選択状態にする', () => {
    window.localStorage.setItem('locale', 'en')
    renderSwitcher()
    expect(screen.getByRole('combobox', { name: '言語選択' })).toHaveValue('en')
  })

  it('不正なlocaleが保存されていても、I18nProviderと同じjaへフォールバックする', () => {
    // 修正前は無検証でキャストしていたため <select value="zz"> となり、
    // どのoptionにも一致せず「本文は日本語、セレクトは未選択」という不整合になっていた。
    window.localStorage.setItem('locale', 'zz')
    renderSwitcher()
    expect(screen.getByRole('combobox', { name: '言語選択' })).toHaveValue('ja')
  })

  it('選択を変えるとlocalStorageへ保存し、ページを再読み込みする', async () => {
    const user = userEvent.setup()
    renderSwitcher()

    await user.selectOptions(screen.getByRole('combobox', { name: '言語選択' }), 'en')

    expect(window.localStorage.getItem('locale')).toBe('en')
    expect(reloadSpy).toHaveBeenCalledTimes(1)
    expect(reloadSpy.mock.calls[0][0]).toEqual(
      expect.objectContaining({ message: expect.stringContaining('Not implemented: navigation') })
    )
  })

  it('選択肢はi18nConfigのlocalesから生成する', () => {
    renderSwitcher()
    const options = screen.getAllByRole('option') as HTMLOptionElement[]
    expect(options.map((o) => o.value)).toEqual(['ja', 'en'])
  })
})
