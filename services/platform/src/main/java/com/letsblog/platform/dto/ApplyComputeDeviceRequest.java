package com.letsblog.platform.dto;

import jakarta.validation.constraints.NotNull;

/** 演算デバイスの適用要求(issue #1399)。 */
public record ApplyComputeDeviceRequest(@NotNull ComputeDevice device) {
}
