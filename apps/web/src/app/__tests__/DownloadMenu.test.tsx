import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { DownloadMenu } from '../DownloadMenu'
import { I18nProvider } from '../I18nProvider'

function renderMenu() {
  return render(
    <I18nProvider>
      <DownloadMenu />
    </I18nProvider>
  )
}

function okResponse(disposition?: string) {
  return {
    ok: true,
    redirected: false,
    status: 200,
    headers: new Headers(disposition ? { 'content-disposition': disposition } : {}),
    blob: async () => new Blob(['x']),
  }
}

describe('DownloadMenu', () => {
  let clicked: { href: string; download: string }[]

  beforeEach(() => {
    clicked = []
    global.URL.createObjectURL = jest.fn(() => 'blob:x')
    global.URL.revokeObjectURL = jest.fn()
    jest
      .spyOn(HTMLAnchorElement.prototype, 'click')
      .mockImplementation(function (this: HTMLAnchorElement) {
        clicked.push({ href: this.href, download: this.download })
      })
  })
  afterEach(() => jest.restoreAllMocks())

  it('is closed by default and lists the VSCode extension item when opened', () => {
    renderMenu()
    expect(screen.queryByRole('menuitem')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'ダウンロード' }))
    expect(screen.getByRole('menuitem', { name: /VSCode拡張機能/ })).toBeInTheDocument()
  })

  it('lists the Penpot plugin and MCP server items too (issue #1491)', () => {
    renderMenu()
    fireEvent.click(screen.getByRole('button', { name: 'ダウンロード' }))
    expect(screen.getAllByRole('menuitem')).toHaveLength(3)
    expect(screen.getByRole('menuitem', { name: /Penpotプラグイン/ })).toBeInTheDocument()
    expect(screen.getByRole('menuitem', { name: /MCPサーバー/ })).toBeInTheDocument()
  })

  it('downloads the Penpot plugin zip from /downloads/penpot-plugin', async () => {
    global.fetch = jest.fn().mockResolvedValue(okResponse())
    renderMenu()
    fireEvent.click(screen.getByRole('button', { name: 'ダウンロード' }))
    fireEvent.click(screen.getByRole('menuitem', { name: /Penpotプラグイン/ }))
    await waitFor(() => expect(clicked).toHaveLength(1))
    expect(global.fetch).toHaveBeenCalledWith('/downloads/penpot-plugin')
    expect(clicked[0].download).toBe('letsblog-penpot-plugin.zip')
  })

  it('downloads the MCP server zip from /downloads/mcp-server', async () => {
    global.fetch = jest.fn().mockResolvedValue(okResponse())
    renderMenu()
    fireEvent.click(screen.getByRole('button', { name: 'ダウンロード' }))
    fireEvent.click(screen.getByRole('menuitem', { name: /MCPサーバー/ }))
    await waitFor(() => expect(clicked).toHaveLength(1))
    expect(global.fetch).toHaveBeenCalledWith('/downloads/mcp-server')
    expect(clicked[0].download).toBe('letsblog-mcp-server.zip')
  })

  it('closes on Escape', () => {
    renderMenu()
    fireEvent.click(screen.getByRole('button', { name: 'ダウンロード' }))
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(screen.queryByRole('menuitem')).toBeNull()
  })

  it('closes on outside click but not on inside click', () => {
    renderMenu()
    fireEvent.click(screen.getByRole('button', { name: 'ダウンロード' }))
    fireEvent.mouseDown(screen.getByRole('menu'))
    expect(screen.getByRole('menu')).toBeInTheDocument()
    fireEvent.mouseDown(document.body)
    expect(screen.queryByRole('menu')).toBeNull()
  })

  it('toggles closed when the trigger is clicked again', () => {
    renderMenu()
    const trigger = screen.getByRole('button', { name: 'ダウンロード' })
    fireEvent.click(trigger)
    fireEvent.click(trigger)
    expect(screen.queryByRole('menu')).toBeNull()
  })

  it('downloads using the filename from content-disposition', async () => {
    global.fetch = jest.fn().mockResolvedValue(okResponse('attachment; filename="ext-1.2.vsix"'))
    renderMenu()
    fireEvent.click(screen.getByRole('button', { name: 'ダウンロード' }))
    fireEvent.click(screen.getByRole('menuitem', { name: /VSCode拡張機能/ }))
    await waitFor(() => expect(clicked).toHaveLength(1))
    expect(global.fetch).toHaveBeenCalledWith('/downloads/vscode-extension')
    expect(clicked[0].download).toBe('ext-1.2.vsix')
  })

  it('falls back to the default filename without content-disposition', async () => {
    global.fetch = jest.fn().mockResolvedValue(okResponse())
    renderMenu()
    fireEvent.click(screen.getByRole('button', { name: 'ダウンロード' }))
    fireEvent.click(screen.getByRole('menuitem', { name: /VSCode拡張機能/ }))
    await waitFor(() => expect(clicked).toHaveLength(1))
    expect(clicked[0].download).toBe('letsblog-vscode.vsix')
  })

  it('shows a pending state while building and disables the item', async () => {
    let resolve: (v: unknown) => void = () => {}
    global.fetch = jest.fn().mockReturnValue(new Promise((r) => (resolve = r)))
    renderMenu()
    fireEvent.click(screen.getByRole('button', { name: 'ダウンロード' }))
    fireEvent.click(screen.getByRole('menuitem', { name: /VSCode拡張機能/ }))
    const item = await screen.findByRole('menuitem', { name: /ビルド中/ })
    expect(item).toBeDisabled()
    resolve(okResponse())
    await waitFor(() => expect(screen.getByRole('menuitem', { name: /ダウンロード \(\.vsix\)|VSCode拡張機能をダウンロード/ })).not.toBeDisabled())
  })

  it('shows an error when the session expired (redirected)', async () => {
    global.fetch = jest.fn().mockResolvedValue({ ...okResponse(), redirected: true })
    renderMenu()
    fireEvent.click(screen.getByRole('button', { name: 'ダウンロード' }))
    fireEvent.click(screen.getByRole('menuitem', { name: /VSCode拡張機能/ }))
    expect(await screen.findByRole('alert')).toHaveTextContent('セッションが切れている')
  })

  it('shows the JSON error message on a failed response', async () => {
    global.fetch = jest.fn().mockResolvedValue({
      ok: false,
      redirected: false,
      status: 500,
      headers: new Headers({ 'content-type': 'application/json' }),
      json: async () => ({ error: 'build failed' }),
    })
    renderMenu()
    fireEvent.click(screen.getByRole('button', { name: 'ダウンロード' }))
    fireEvent.click(screen.getByRole('menuitem', { name: /VSCode拡張機能/ }))
    expect(await screen.findByRole('alert')).toHaveTextContent('build failed')
  })

  it('shows the text body on a non-JSON failure, and a status fallback when empty', async () => {
    global.fetch = jest.fn().mockResolvedValue({
      ok: false,
      redirected: false,
      status: 502,
      headers: new Headers({ 'content-type': 'text/plain' }),
      text: async () => '',
    })
    renderMenu()
    fireEvent.click(screen.getByRole('button', { name: 'ダウンロード' }))
    fireEvent.click(screen.getByRole('menuitem', { name: /VSCode拡張機能/ }))
    expect(await screen.findByRole('alert')).toHaveTextContent('HTTP 502')
  })

  it('shows a stringified non-Error rejection and treats a missing content-type as text', async () => {
    global.fetch = jest.fn().mockRejectedValue('boom')
    renderMenu()
    fireEvent.click(screen.getByRole('button', { name: 'ダウンロード' }))
    fireEvent.click(screen.getByRole('menuitem', { name: /VSCode拡張機能/ }))
    expect(await screen.findByRole('alert')).toHaveTextContent('boom')
  })

  it('uses the missing content-type branch on failure', async () => {
    global.fetch = jest.fn().mockResolvedValue({
      ok: false,
      redirected: false,
      status: 500,
      headers: new Headers(),
      text: async () => 'plain failure',
    })
    renderMenu()
    fireEvent.click(screen.getByRole('button', { name: 'ダウンロード' }))
    fireEvent.click(screen.getByRole('menuitem', { name: /VSCode拡張機能/ }))
    expect(await screen.findByRole('alert')).toHaveTextContent('plain failure')
  })

  it('still shows the error when the menu was closed during the download', async () => {
    let reject: (e: Error) => void = () => {}
    global.fetch = jest.fn().mockReturnValue(new Promise((_, r) => (reject = r)))
    renderMenu()
    fireEvent.click(screen.getByRole('button', { name: 'ダウンロード' }))
    fireEvent.click(screen.getByRole('menuitem', { name: /VSCode拡張機能/ }))
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(screen.queryByRole('menu')).toBeNull()
    reject(new Error('late failure'))
    expect(await screen.findByRole('alert')).toHaveTextContent('late failure')
  })

  it('keeps the panel inside the viewport on narrow screens', () => {
    renderMenu()
    fireEvent.click(screen.getByRole('button', { name: 'ダウンロード' }))
    const cls = screen.getByRole('menu').className
    expect(cls).toContain('max-sm:fixed')
    expect(cls).toContain('max-sm:left-4')
    expect(cls).toContain('max-sm:right-4')
  })
})
