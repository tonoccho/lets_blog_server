package com.letsblog.platform.controller;

import com.letsblog.platform.service.VscodeExtensionBuildService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * システム画面からのVSCode拡張機能(.vsix)オンデマンドビルド・ダウンロード。legacy-apiの
 * VscodeExtensionControllerと同じAPI形状のままplatform-serviceへ移設したもの
 * (issue #696、C10-4)。gatewayの{@code /api/system/**}ルート(platform)を経由する。
 */
@RestController
@RequestMapping("/api/system/vscode-extension")
public class VscodeExtensionController {

    private final VscodeExtensionBuildService vscodeExtensionBuildService;

    public VscodeExtensionController(VscodeExtensionBuildService vscodeExtensionBuildService) {
        this.vscodeExtensionBuildService = vscodeExtensionBuildService;
    }

    /**
     * 認可不要: 本システム用のVSCode拡張(.vsix)を配布する(issue #830)。
     * 拡張を入れられること自体が全利用者に必要で、配布物に利用者固有のデータは含まれない。
     */
    @GetMapping
    public ResponseEntity<Resource> download() {
        VscodeExtensionBuildService.BuiltExtension built = vscodeExtensionBuildService.buildAndGetVsix();
        Resource resource = new FileSystemResource(built.vsixPath());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + built.filename() + "\"")
                .body(resource);
    }
}
