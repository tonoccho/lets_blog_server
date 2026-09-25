package com.letsblog.project.controller;

import com.letsblog.project.dto.SshKeyPairCreateRequest;
import com.letsblog.project.dto.SshKeyPairGeneratedResponse;
import com.letsblog.project.dto.SshKeyPairSummaryResponse;
import com.letsblog.project.service.SshKeyPairService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 名前をつけて保存・管理するSSH鍵ペア(Ed25519)のadmin限定管理API。
 * 秘密鍵は生成直後のレスポンス(POST)でのみ返し、一覧では公開鍵のみを返す。
 * 権限確認・監査ログ記録はSshKeyPairService側で行う。
 */
@RestController
@RequestMapping("/api/ssh-key-pairs")
public class SshKeyPairController {

    private final SshKeyPairService sshKeyPairService;

    public SshKeyPairController(SshKeyPairService sshKeyPairService) {
        this.sshKeyPairService = sshKeyPairService;
    }

    @GetMapping
    public List<SshKeyPairSummaryResponse> list() {
        return sshKeyPairService.list();
    }

    @PostMapping
    public ResponseEntity<SshKeyPairGeneratedResponse> generate(@Valid @RequestBody SshKeyPairCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(sshKeyPairService.generate(request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        sshKeyPairService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
