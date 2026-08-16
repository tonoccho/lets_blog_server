package com.letsblog.api.service;

import com.letsblog.api.aop.AuditLog;
import com.letsblog.api.crypto.CredentialCipher;
import com.letsblog.api.crypto.SshKeyGenerationService;
import com.letsblog.api.domain.AuditLogAction;
import com.letsblog.api.domain.SshKeyPair;
import com.letsblog.api.dto.SshKeyPairCreateRequest;
import com.letsblog.api.dto.SshKeyPairGeneratedResponse;
import com.letsblog.api.dto.SshKeyPairSummaryResponse;
import com.letsblog.api.repository.SshKeyPairRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 名前をつけて保存・管理するSSH鍵ペア(Ed25519)を扱う。鍵の生成自体は既存の
 * SshKeyGenerationService(ホストのssh-keygenに委譲)を再利用し、秘密鍵は
 * CredentialCipherで暗号化してDBに保存する。秘密鍵は生成直後のレスポンスでのみ返し、
 * 一覧・詳細取得では公開鍵のみを返す(生成後は秘密鍵をAPI経由で再取得できない)。
 */
@Service
public class SshKeyPairService {

    private final SshKeyPairRepository repository;
    private final SshKeyGenerationService sshKeyGenerationService;
    private final CredentialCipher credentialCipher;
    private final AdminAuthorizationService adminAuthorizationService;

    public SshKeyPairService(
            SshKeyPairRepository repository,
            SshKeyGenerationService sshKeyGenerationService,
            CredentialCipher credentialCipher,
            AdminAuthorizationService adminAuthorizationService) {
        this.repository = repository;
        this.sshKeyGenerationService = sshKeyGenerationService;
        this.credentialCipher = credentialCipher;
        this.adminAuthorizationService = adminAuthorizationService;
    }

    @Transactional(readOnly = true)
    public List<SshKeyPairSummaryResponse> list() {
        adminAuthorizationService.requireAdmin();
        return repository.findAll().stream()
                .map(SshKeyPairSummaryResponse::from)
                .toList();
    }

    @AuditLog(action = AuditLogAction.SSH_KEY_PAIR_CREATED, resourceType = "SSH_KEY_PAIR")
    @Transactional
    public SshKeyPairGeneratedResponse generate(SshKeyPairCreateRequest request) {
        adminAuthorizationService.requireAdmin();
        if (repository.existsByName(request.name())) {
            throw new IllegalArgumentException("名前 '" + request.name() + "' は既に登録されています");
        }

        SshKeyGenerationService.SshKeyPair keyPair = sshKeyGenerationService.generateEd25519(request.comment());
        SshKeyPair entity = new SshKeyPair(
                request.name(),
                request.comment(),
                keyPair.publicKeyLine(),
                credentialCipher.encrypt(keyPair.privateKeyPem()));
        repository.save(entity);

        return SshKeyPairGeneratedResponse.of(entity, keyPair.privateKeyPem());
    }

    @AuditLog(action = AuditLogAction.SSH_KEY_PAIR_DELETED, resourceType = "SSH_KEY_PAIR")
    @Transactional
    public void delete(Long id) {
        adminAuthorizationService.requireAdmin();
        if (!repository.existsById(id)) {
            throw new SshKeyPairNotFoundException("SSH鍵ペアが見つかりません: id=" + id);
        }
        repository.deleteById(id);
    }
}
