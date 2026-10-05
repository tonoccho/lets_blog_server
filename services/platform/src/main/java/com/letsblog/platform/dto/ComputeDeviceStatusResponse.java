package com.letsblog.platform.dto;

import java.time.Instant;

/**
 * 演算デバイス切り替えの状態(issue #1399)。現在の構成は<b>実際に動いているコンテナ</b>から判定した値で、
 * DBに保存した選択値ではない(保存値と実際の構成が食い違う画面を作らないため)。
 *
 * @param target               切り替え対象(例: {@code comfyui})
 * @param currentDevice        いま動いている構成
 * @param cpuFixed             GPU構成のコンテナが無く、CPUに固定されているか
 * @param gpuSelectable        GPUを選べるか
 * @param cpuSelectable        CPUを選べるか
 * @param gpuUnavailableReason GPUを選べない理由(選べるときはnull)
 * @param cpuUnavailableReason CPUを選べない理由(選べるときはnull)
 * @param apply                直近の適用の進行状態
 */
public record ComputeDeviceStatusResponse(
        String target,
        CurrentDevice currentDevice,
        boolean cpuFixed,
        boolean gpuSelectable,
        boolean cpuSelectable,
        String gpuUnavailableReason,
        String cpuUnavailableReason,
        ApplyStatus apply) {

    /** 動いているコンテナから判定した現在の構成。 */
    public enum CurrentDevice {
        GPU,
        CPU,
        /** どちらも動いていない。 */
        NONE,
        /** 両方動いている(同じネットワークエイリアスが重複する異常な状態)。 */
        BOTH
    }

    /** 適用の進行状態。 */
    public enum ApplyState {
        IDLE,
        APPLYING,
        SUCCEEDED,
        FAILED
    }

    /**
     * @param requestedDevice 要求された構成(IDLEのときnull)
     * @param message         成功の補足、または失敗の理由(どの段階で何が起きたか)
     */
    public record ApplyStatus(
            ApplyState state, ComputeDevice requestedDevice, String message, Instant startedAt, Instant finishedAt) {
    }
}
