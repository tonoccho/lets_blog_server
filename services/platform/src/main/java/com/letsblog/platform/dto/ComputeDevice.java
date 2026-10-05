package com.letsblog.platform.dto;

/** 切り替え対象サービスの演算デバイス構成(issue #1399)。NPUは #1398 の結論が出てから足す。 */
public enum ComputeDevice {
    GPU,
    CPU
}
