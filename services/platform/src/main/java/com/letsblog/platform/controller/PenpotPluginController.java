package com.letsblog.platform.controller;

import com.letsblog.platform.service.SourceArchiveService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PenpotプラグインのソースZip配布(issue #1491)。gatewayの{@code /api/system/**}ルート(platform)を経由する。
 */
@RestController
@RequestMapping("/api/system/penpot-plugin")
public class PenpotPluginController {

    private final SourceArchiveService sourceArchiveService;

    public PenpotPluginController(SourceArchiveService sourceArchiveService) {
        this.sourceArchiveService = sourceArchiveService;
    }

    /**
     * 認可不要: Penpotプラグインのソース一式(+ setup.sh)を配布する(issue #1491)。
     * VSCode拡張(#830)と同じく、入れられること自体が全利用者に必要で、
     * 配布物に利用者固有のデータは含まれない。未認証は{@code SecurityConfig}が401にする。
     */
    @GetMapping
    public ResponseEntity<byte[]> download() {
        SourceArchiveService.SourceArchive archive = sourceArchiveService.penpotPlugin();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + archive.filename() + "\"")
                .body(archive.bytes());
    }
}
