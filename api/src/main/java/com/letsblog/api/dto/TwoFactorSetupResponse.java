package com.letsblog.api.dto;

import java.util.List;

public record TwoFactorSetupResponse(String qrCodeDataUrl, List<String> backupCodes) {
}
