package com.letsblog.api.controller;

import com.letsblog.api.service.VscodeExtensionBuildService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system/vscode-extension")
public class VscodeExtensionController {

    private final VscodeExtensionBuildService vscodeExtensionBuildService;

    public VscodeExtensionController(VscodeExtensionBuildService vscodeExtensionBuildService) {
        this.vscodeExtensionBuildService = vscodeExtensionBuildService;
    }

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
