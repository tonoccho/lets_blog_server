package com.letsblog.platform.controller;

import com.letsblog.platform.service.PluginRegistrationService;
import com.letsblog.platform.service.PluginRegistrationService.ResolvedRegistration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 種類を指定して、システム全体の使用中の登録を返す内部API(issue #1807、docs/plugins/CONTRACTS.md §4.2)。
 * {@link InternalPlatformSettingsController}と同じく{@code /api/internal/platform/**}で、gatewayには載らず、
 * JWT必須・ロール判定なし(認証済みのサービスが呼ぶ)。登録が無ければ{@code connected=false}の未接続を返す。
 */
@RestController
public class InternalPluginRegistrationController {

    private final PluginRegistrationService service;

    public InternalPluginRegistrationController(PluginRegistrationService service) {
        this.service = service;
    }

    @GetMapping("/api/internal/platform/plugin-registrations/resolve")
    public ResolvedRegistration resolve(@RequestParam String kind) {
        return service.resolve(kind);
    }
}
