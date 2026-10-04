import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { AppSettingsPanel } from '../AppSettingsPanel'
import { updateAppSettingsAction } from '../actions'
import type { AppSetting } from '@/lib/apiClient'

jest.mock('../actions', () => ({
  updateAppSettingsAction: jest.fn(),
}))

const updateAppSettingsActionMock = updateAppSettingsAction as jest.MockedFunction<
  typeof updateAppSettingsAction
>

function setting(overrides: Partial<AppSetting> & { key: string }): AppSetting {
  return {
    label: overrides.key,
    secret: false,
    configured: true,
    source: 'ENVIRONMENT',
    value: 'value',
    ...overrides,
  }
}

/** システム設定画面が返す全キー(AppSettingService.DEFINITIONS 相当)。 */
function allSettings(): AppSetting[] {
  return [
    setting({ key: 'llm_provider', label: 'AIプロバイダー', value: 'OLLAMA' }),
    setting({ key: 'llm_base_url', label: 'LLM ベースURL', value: 'https://api.openai.com/v1' }),
    setting({ key: 'llm_model', label: 'LLM 既定モデル', value: 'gpt-4o-mini', source: 'DATABASE' }),
    setting({ key: 'llm_available_models', label: 'LLM 選択可能モデル', value: 'gpt-4o-mini' }),
    setting({ key: 'llm_request_timeout_seconds', label: 'LLM タイムアウト', value: '120' }),
    setting({
      key: 'llm_ollama_base_url',
      label: 'Ollama ベースURL',
      value: 'http://ollama:11434/v1',
      source: 'ENVIRONMENT',
    }),
    setting({
      key: 'llm_ollama_model',
      label: 'Ollama 既定モデル',
      value: 'qwen2.5:7b-instruct',
      source: 'DATABASE',
    }),
    setting({ key: 'llm_ollama_available_models', label: 'Ollama 選択可能モデル', value: 'qwen2.5:7b-instruct' }),
    setting({ key: 'llm_claude_available_models', label: 'Claude 選択可能モデル', value: 'claude-3-5-haiku-20241022' }),
    setting({ key: 'llm_claude_model', label: 'Claude 既定モデル', value: 'claude-3-5-haiku-20241022' }),
    setting({ key: 'comfyui_base_url', label: 'ComfyUI ベースURL', value: 'http://comfyui:8188' }),
    setting({ key: 'image_llm_base_url', label: '画像生成 ベースURL', value: 'https://api.openai.com/v1' }),
    setting({ key: 'mail_host', label: 'メール送信ホスト', value: 'smtp.example.com' }),
    setting({ key: 'mail_port', label: 'メール送信ポート', value: '587' }),
    setting({ key: 'mail_username', label: 'メール送信ユーザー名', value: 'user' }),
    setting({ key: 'mail_password', label: 'メール送信パスワード', secret: true, value: null }),
    setting({ key: 'app_mail_from', label: 'メール送信元アドレス', value: 'noreply@example.com' }),
    setting({ key: 'app_web_base_url', label: 'Webフロントの公開URL', value: 'https://localhost' }),
    setting({ key: 'site_admin_path', label: '管理画面パス', value: 'wp-admin' }),
    setting({
      key: 'upload_rate_limit_requests',
      label: 'レート制限',
      value: null,
      configured: false,
      source: 'NONE',
    }),
  ]
}

function field(name: string): HTMLElement | null {
  return document.querySelector(`[name="${name}"]`)
}

describe('AppSettingsPanel', () => {
  beforeEach(() => {
    updateAppSettingsActionMock.mockReset()
  })

  it('画像生成用のAPIキー入力欄は表示せず、ベースURLだけを画像生成AI連携グループに描画する(issue #1521)', () => {
    // サーバーが旧バージョンの行を返しても入力欄は出ない
    const settings = [
      ...allSettings(),
      setting({ key: 'image_llm_api_key', label: '画像生成 APIキー', secret: true, value: null }),
    ]
    render(<AppSettingsPanel settings={settings} />)

    expect(field('image_llm_api_key')).toBeNull()
    const section = field('comfyui_base_url')!.closest('section')
    expect(section).toContainElement(field('image_llm_base_url'))
    expect(section!.querySelector('p')!.textContent ?? '').not.toContain('APIキー')
  })

  it('OLLAMA専用の接続設定キーを外部LLMサービス連携グループに描画する', () => {
    render(<AppSettingsPanel settings={allSettings()} />)

    const baseUrl = field('llm_ollama_base_url')
    const model = field('llm_ollama_model')

    expect(baseUrl).not.toBeNull()
    expect(model).not.toBeNull()
    expect((baseUrl as HTMLInputElement).value).toBe('http://ollama:11434/v1')
    expect((model as HTMLInputElement).value).toBe('qwen2.5:7b-instruct')
    const llmSection = field('llm_provider')!.closest('section')
    expect(llmSection).toContainElement(baseUrl)
    expect(llmSection).toContainElement(model)
  })

  it('provider別の選択可能モデルのキーを外部LLMサービス連携グループに描画する(issue #1088)', () => {
    render(<AppSettingsPanel settings={allSettings()} />)

    const llmSection = field('llm_provider')!.closest('section')
    for (const name of ['llm_ollama_available_models', 'llm_claude_available_models']) {
      const input = field(name)
      expect(input).not.toBeNull()
      expect(llmSection).toContainElement(input)
    }
  })

  it('管理画面パスの設定項目を専用グループに描画し、説明文で用途を案内する(issue #1079)', () => {
    render(<AppSettingsPanel settings={allSettings()} />)

    const input = field('site_admin_path') as HTMLInputElement
    expect(input).not.toBeNull()
    expect(input.value).toBe('wp-admin')
    const section = input.closest('section')!
    expect(section.querySelector('h2')!.textContent).toBe('サイトの管理画面パス')
    const description = section.querySelector('p')!.textContent ?? ''
    expect(description).toContain('サイト一覧の管理画面リンク')
    expect(description).toContain('サイトごとの上書き')
    expect(description).toContain('空欄で保存すると環境変数の値に戻')
  })

  it('外部LLMサービス連携の説明文がOLLAMAとOPENAIの設定共用を謳わない', () => {
    render(<AppSettingsPanel settings={allSettings()} />)

    const description = field('llm_provider')!.closest('section')!.querySelector('p')!.textContent ?? ''

    expect(description).not.toContain('共用')
    expect(description).toContain('llm_ollama_base_url')
  })

  it('llm_providerはOLLAMA/OPENAI/CLAUDEから選ぶセレクトになる', () => {
    render(<AppSettingsPanel settings={allSettings()} />)

    const select = field('llm_provider') as HTMLSelectElement
    expect(select.tagName).toBe('SELECT')
    expect(Array.from(select.options).map((option) => option.value)).toEqual([
      '',
      'OLLAMA',
      'OPENAI',
      'CLAUDE',
    ])
    expect(select.value).toBe('OLLAMA')
  })

  it('llm_providerが未設定なら未選択のセレクトになる', () => {
    render(
      <AppSettingsPanel
        settings={[setting({ key: 'llm_provider', value: null, configured: false, source: 'NONE' })]}
      />
    )

    expect((field('llm_provider') as HTMLSelectElement).value).toBe('')
  })

  it('秘匿項目は値を持たないパスワード入力にし、設定状況をプレースホルダで示す', () => {
    render(<AppSettingsPanel settings={allSettings()} />)

    const configured = field('mail_password') as HTMLInputElement

    expect(configured.type).toBe('password')
    expect(configured.value).toBe('')
    expect(configured.placeholder).toBe('設定済み(変更する場合のみ入力)')
  })

  it('ChatGPT / Claude のAPIキーはプロジェクト単位だけなので、サーバーが旧行を返しても入力欄を出さない(issue #1568)', () => {
    const settings = [
      ...allSettings(),
      setting({ key: 'llm_api_key', label: 'LLM APIキー', secret: true, value: null }),
      setting({ key: 'llm_claude_api_key', label: 'Claude APIキー', secret: true, value: null }),
    ]
    render(<AppSettingsPanel settings={settings} />)

    expect(field('llm_api_key')).toBeNull()
    expect(field('llm_claude_api_key')).toBeNull()
    // キー以外のLLM設定は残る
    expect(field('llm_base_url')).not.toBeNull()
    expect(field('llm_claude_model')).not.toBeNull()
    const section = field('llm_provider')!.closest('section')
    const description = section!.querySelector('p')!.textContent ?? ''
    expect(description).not.toContain('llm_api_key')
    expect(description).not.toContain('llm_claude_api_key')
  })

  it('設定値の取得元をラベル行に表示する', () => {
    render(<AppSettingsPanel settings={allSettings()} />)

    expect(screen.getAllByText('DB設定を使用中').length).toBeGreaterThan(0)
    expect(screen.getAllByText('環境変数を使用中').length).toBeGreaterThan(0)
    expect(screen.getAllByText('未設定').length).toBeGreaterThan(0)
  })

  it('APIサーバーが返さないキーは描画しない', () => {
    render(<AppSettingsPanel settings={[setting({ key: 'llm_provider', value: 'OLLAMA' })]} />)

    expect(field('llm_ollama_base_url')).toBeNull()
    expect(field('llm_api_key')).toBeNull()
  })

  it('保存に失敗するとエラーを表示する', async () => {
    updateAppSettingsActionMock.mockResolvedValue({ error: 'llm_ollama_base_url はhttp://またはhttps://から始まるURLを指定してください' })
    render(<AppSettingsPanel settings={allSettings()} />)

    fireEvent.click(screen.getByRole('button', { name: 'まとめて保存' }))

    await waitFor(() => {
      expect(screen.getByText(/http:\/\/またはhttps:\/\/から始まるURL/)).toBeInTheDocument()
    })
  })

  it('保存に成功すると成功メッセージを表示する', async () => {
    updateAppSettingsActionMock.mockResolvedValue({ success: true })
    render(<AppSettingsPanel settings={allSettings()} />)

    fireEvent.click(screen.getByRole('button', { name: 'まとめて保存' }))

    await waitFor(() => {
      expect(screen.getByText('保存しました。')).toBeInTheDocument()
    })
  })
})
