package com.letsblog.platform.controller;

import com.letsblog.platform.service.PluginRegistrationService;
import com.letsblog.platform.service.PluginRegistrationService.RegistrationView;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * システム全体のプラグインの登録の管理者向けAPI(issue #1807)。gatewayの{@code /api/system-settings/**}を経由する。
 * 認可(admin限定)はサービス層のrequireAdminが担う。
 */
@RestController
@RequestMapping("/api/system-settings/plugin-registrations")
public class PluginRegistrationController {

    public record CreateRequest(String kind, String name, String url, String drawioUrl) {
    }

    public record UpdateRequest(String name, String url, String drawioUrl) {
    }

    private final PluginRegistrationService pluginRegistrationService;

    public PluginRegistrationController(PluginRegistrationService service) {
        this.pluginRegistrationService = service;
    }

    @GetMapping
    public List<RegistrationView> list() {
        return pluginRegistrationService.list();
    }

    @PostMapping
    public RegistrationView create(@RequestBody CreateRequest request) {
        return pluginRegistrationService.create(request.kind(), request.name(), request.url(), request.drawioUrl());
    }

    @PutMapping("/{id}")
    public RegistrationView update(@PathVariable Long id, @RequestBody UpdateRequest request) {
        return pluginRegistrationService.update(id, request.name(), request.url(), request.drawioUrl());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        pluginRegistrationService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/in-use")
    public RegistrationView activate(@PathVariable Long id) {
        return pluginRegistrationService.activate(id);
    }
}
