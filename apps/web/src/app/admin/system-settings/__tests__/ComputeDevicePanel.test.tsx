import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { ComputeDevicePanel } from '../ComputeDevicePanel'
import { applyComputeDeviceAction } from '../actions'
import type { ComputeDeviceStatus } from '@/lib/apiClient'

jest.mock('../actions', () => ({
  updateAppSettingsAction: jest.fn(),
  applyComputeDeviceAction: jest.fn(),
}))

const refresh = jest.fn()
jest.mock('next/navigation', () => ({
  useRouter: () => ({ refresh }),
}))

const actionMock = applyComputeDeviceAction as jest.MockedFunction<typeof applyComputeDeviceAction>

function status(overrides: Partial<ComputeDeviceStatus> = {}): ComputeDeviceStatus {
  return {
    target: 'comfyui',
    currentDevice: 'GPU',
    cpuFixed: false,
    gpuSelectable: true,
    cpuSelectable: true,
    gpuUnavailableReason: null,
    cpuUnavailableReason: null,
    apply: { state: 'IDLE', requestedDevice: null, message: null, startedAt: null, finishedAt: null },
    ...overrides,
  }
}

function radio(name: string): HTMLInputElement {
  return screen.getByRole('radio', { name }) as HTMLInputElement
}

describe('ComputeDevicePanel(issue #1399)', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    jest.useRealTimers()
  })

  it('「適用する操作」として見出し付きで現在の構成を表示する', () => {
    render(<ComputeDevicePanel status={status()} />)

    expect(screen.getByRole('heading', { name: '演算デバイス(ComfyUI)' })).toBeInTheDocument()
    expect(screen.getByTestId('compute-device-current')).toHaveTextContent('現在の構成: GPU')
  })

  it.each([
    ['CPU', false, '現在の構成: CPU'],
    ['NONE', false, '現在の構成: 停止中'],
    ['BOTH', false, '現在の構成: GPU と CPU の両方が稼働中'],
    ['CPU', true, '現在の構成: CPU(固定)'],
  ] as const)('現在の構成 %s(固定=%s)を「%s」と表示する', (currentDevice, cpuFixed, text) => {
    render(<ComputeDevicePanel status={status({ currentDevice, cpuFixed })} />)

    expect(screen.getByTestId('compute-device-current')).toHaveTextContent(text)
  })

  it('両方選べるときは今と違う方を既定で選び、適用で選んだ値を送る', async () => {
    actionMock.mockResolvedValue({ success: true })
    render(<ComputeDevicePanel status={status()} />)

    expect(radio('CPU').checked).toBe(true)
    expect(radio('GPU').checked).toBe(false)
    fireEvent.click(screen.getByRole('button', { name: '適用する' }))

    await waitFor(() => expect(actionMock).toHaveBeenCalled())
    const form = actionMock.mock.calls[0][1] as FormData
    expect(form.get('device')).toBe('CPU')
    expect(await screen.findByText('適用を受け付けました。')).toBeInTheDocument()
  })

  it('選び直せる', () => {
    render(<ComputeDevicePanel status={status({ currentDevice: 'NONE' })} />)

    fireEvent.click(radio('GPU'))

    expect(radio('GPU').checked).toBe(true)
  })

  it('GPU構成が無いホストではGPUを選べず理由を表示し、CPUに固定される', () => {
    render(
      <ComputeDevicePanel
        status={status({
          currentDevice: 'CPU',
          cpuFixed: true,
          gpuSelectable: false,
          gpuUnavailableReason: 'このホストには GPU 構成の ComfyUI が無いため CPU 固定です。',
        })}
      />,
    )

    expect(radio('GPU').disabled).toBe(true)
    expect(screen.getByText('このホストには GPU 構成の ComfyUI が無いため CPU 固定です。')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '適用する' })).toBeDisabled()
  })

  it('CPU構成が無いときはCPUを選べず作成手順を表示する', () => {
    render(
      <ComputeDevicePanel
        status={status({
          cpuSelectable: false,
          cpuUnavailableReason: 'docker compose --profile cpu create comfyui-cpu を実行してください。',
        })}
      />,
    )

    expect(radio('CPU').disabled).toBe(true)
    expect(screen.getByText(/docker compose --profile cpu create comfyui-cpu/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '適用する' })).toBeDisabled()
  })

  it('何も選べない構成では両方とも無効', () => {
    render(
      <ComputeDevicePanel
        status={status({ currentDevice: 'NONE', gpuSelectable: false, cpuSelectable: false, cpuFixed: true })}
      />,
    )

    expect(radio('GPU').disabled).toBe(true)
    expect(radio('CPU').disabled).toBe(true)
  })

  it('適用に失敗した理由(API拒否)を警告として表示する', async () => {
    actionMock.mockResolvedValue({ error: 'APIエラー (409): 適用中です' })
    render(<ComputeDevicePanel status={status()} />)

    fireEvent.click(screen.getByRole('button', { name: '適用する' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('APIエラー (409): 適用中です')
  })

  it('適用中は「適用中」を表示してボタンを無効にし、一定間隔で再取得する', () => {
    jest.useFakeTimers()
    const { unmount } = render(
      <ComputeDevicePanel
        status={status({
          apply: { state: 'APPLYING', requestedDevice: 'CPU', message: null, startedAt: 't', finishedAt: null },
        })}
      />,
    )

    expect(screen.getByTestId('compute-device-apply-state')).toHaveTextContent('適用中')
    expect(screen.getByRole('button', { name: '適用する' })).toBeDisabled()
    expect(refresh).not.toHaveBeenCalled()
    act(() => {
      jest.advanceTimersByTime(2000)
    })
    expect(refresh).toHaveBeenCalledTimes(1)
    act(() => {
      jest.advanceTimersByTime(4000)
    })
    expect(refresh).toHaveBeenCalledTimes(3)
    unmount()
    act(() => {
      jest.advanceTimersByTime(4000)
    })
    expect(refresh).toHaveBeenCalledTimes(3)
  })

  it('適用中でなければ再取得しない', () => {
    jest.useFakeTimers()
    render(<ComputeDevicePanel status={status()} />)

    act(() => {
      jest.advanceTimersByTime(10000)
    })

    expect(refresh).not.toHaveBeenCalled()
  })

  it('完了を表示する', () => {
    render(
      <ComputeDevicePanel
        status={status({
          currentDevice: 'CPU',
          apply: { state: 'SUCCEEDED', requestedDevice: 'CPU', message: null, startedAt: 't', finishedAt: 't2' },
        })}
      />,
    )

    expect(screen.getByTestId('compute-device-apply-state')).toHaveTextContent('適用が完了しました')
  })

  it('完了メッセージがあれば併記する', () => {
    render(
      <ComputeDevicePanel
        status={status({
          apply: { state: 'SUCCEEDED', requestedDevice: 'CPU', message: 'CPU構成で稼働中', startedAt: 't', finishedAt: 't2' },
        })}
      />,
    )

    expect(screen.getByTestId('compute-device-apply-state')).toHaveTextContent('CPU構成で稼働中')
  })

  it('失敗とその理由を表示する', () => {
    render(
      <ComputeDevicePanel
        status={status({
          apply: {
            state: 'FAILED',
            requestedDevice: 'CPU',
            message: 'CPU構成の起動: 30秒以内に完了しませんでした。元の構成(GPU)に戻しました。',
            startedAt: 't',
            finishedAt: 't2',
          },
        })}
      />,
    )

    const state = screen.getByTestId('compute-device-apply-state')
    expect(state).toHaveTextContent('適用に失敗しました')
    expect(state).toHaveTextContent('元の構成(GPU)に戻しました。')
  })

  it('適用の履歴が無い(IDLE)ときは進行状態を出さない', () => {
    render(<ComputeDevicePanel status={status()} />)

    expect(screen.queryByTestId('compute-device-apply-state')).toBeNull()
  })
})
