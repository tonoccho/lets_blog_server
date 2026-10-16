import { render, screen } from '@testing-library/react'
import { ValidationPanel } from '../ValidationPanel'

/** 検証結果の表示。純粋な表示部品なので、何も差し替えない(#1834)。 */
describe('ValidationPanel', () => {
  it('検証中は「検証中...」だけを表示する', () => {
    render(<ValidationPanel result={null} isLoading={true} error="無視される" />)

    expect(screen.getByText('検証中...')).toBeInTheDocument()
    expect(screen.queryByText('検証エラー')).not.toBeInTheDocument()
  })

  it('エラーがあれば検証エラーとして表示する', () => {
    render(<ValidationPanel result={null} isLoading={false} error="APIエラー (503): down" />)

    expect(screen.getByText('検証エラー')).toBeInTheDocument()
    expect(screen.getByText('APIエラー (503): down')).toBeInTheDocument()
  })

  it('結果が無ければ何も表示しない', () => {
    const { container } = render(<ValidationPanel result={null} isLoading={false} error={null} />)

    expect(container).toBeEmptyDOMElement()
  })

  it('エラーも警告も無ければ検証成功を表示する', () => {
    render(<ValidationPanel result={{ isValid: true, errors: [], warnings: [] }} isLoading={false} error={null} />)

    expect(screen.getByText('✓ 検証成功')).toBeInTheDocument()
    expect(screen.getByText('HTMLとCSSは有効です。')).toBeInTheDocument()
  })

  it('エラーと警告を、行番号があれば行番号つきで件数とともに表示する', () => {
    render(
      <ValidationPanel
        result={{
          isValid: false,
          errors: [
            { type: 'SYNTAX', message: '閉じタグがありません', line: 3 },
            { type: 'UNKNOWN', message: '行不明のエラー' },
          ],
          warnings: [{ type: 'STYLE', message: '非推奨です', line: 5 }, { type: 'STYLE2', message: '行不明の警告' }],
        } as never}
        isLoading={false}
        error={null}
      />
    )

    expect(screen.getByText('エラー (2)')).toBeInTheDocument()
    expect(screen.getByText('警告 (2)')).toBeInTheDocument()
    expect(screen.getByText(/行 3:\s*SYNTAX/)).toBeInTheDocument()
    expect(screen.getByText(/行 5:\s*STYLE$/)).toBeInTheDocument()
    expect(screen.getByText('閉じタグがありません')).toBeInTheDocument()
    expect(screen.getByText('行不明のエラー')).toBeInTheDocument()
    expect(screen.queryByText(/行 :/)).not.toBeInTheDocument()
    expect(screen.queryByText('✓ 検証成功')).not.toBeInTheDocument()
  })

  it('警告だけのときはエラーの見出しを出さない', () => {
    render(
      <ValidationPanel
        result={{ isValid: true, errors: [], warnings: [{ type: 'STYLE', message: '非推奨です' }] } as never}
        isLoading={false}
        error={null}
      />
    )

    expect(screen.getByText('警告 (1)')).toBeInTheDocument()
    expect(screen.queryByText(/^エラー/)).not.toBeInTheDocument()
  })

  it('エラーだけのときは警告の見出しを出さない', () => {
    render(
      <ValidationPanel
        result={{ isValid: false, errors: [{ type: 'SYNTAX', message: 'x' }], warnings: [] } as never}
        isLoading={false}
        error={null}
      />
    )

    expect(screen.getByText('エラー (1)')).toBeInTheDocument()
    expect(screen.queryByText(/^警告/)).not.toBeInTheDocument()
  })
})
