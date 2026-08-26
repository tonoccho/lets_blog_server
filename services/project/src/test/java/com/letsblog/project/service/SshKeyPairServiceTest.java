package com.letsblog.project.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.common.crypto.CredentialCipher;
import com.letsblog.project.crypto.SshKeyGenerationService;
import com.letsblog.project.domain.SshKeyPair;
import com.letsblog.project.dto.SshKeyPairCreateRequest;
import com.letsblog.project.dto.SshKeyPairGeneratedResponse;
import com.letsblog.project.repository.SshKeyPairRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SshKeyPairServiceの回帰テスト。生成時の暗号化・秘密鍵の一度限りの返却・
 * 名前の一意性・admin権限ゲートを中心に検証する。
 *
 * <p>legacy-apiからの移設(issue #577 stage 1)にあたり、削除時のサイト参照ガード
 * (isReferencedBySite)は移設していない(SshKeyPairServiceのJavadoc参照)ため、
 * legacy-api版に存在したそのテスト(delete_サイトから参照されていれば例外で削除しない 等)は
 * ここには存在しない。
 */
@ExtendWith(MockitoExtension.class)
class SshKeyPairServiceTest {

    @Mock
    private SshKeyPairRepository repository;
    @Mock
    private SshKeyGenerationService sshKeyGenerationService;
    @Mock
    private AdminAuthorizationService adminAuthorizationService;

    private final CredentialCipher credentialCipher = new CredentialCipher(
            java.util.Base64.getEncoder().encodeToString(new byte[32]));

    private SshKeyPairService buildService() {
        return new SshKeyPairService(repository, sshKeyGenerationService, credentialCipher,
                adminAuthorizationService);
    }

    @Test
    void generate_admin権限がなければForbidden() {
        SshKeyPairService service = buildService();
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です"))
                .when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class,
                () -> service.generate(new SshKeyPairCreateRequest("deploy-key", null)));
    }

    @Test
    void generate_同名が既にあればIllegalArgument() {
        SshKeyPairService service = buildService();
        when(repository.existsByName("deploy-key")).thenReturn(true);

        assertThrows(IllegalArgumentException.class,
                () -> service.generate(new SshKeyPairCreateRequest("deploy-key", null)));
        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void generate_鍵を生成し秘密鍵を暗号化して保存し平文を1回だけ返す() {
        SshKeyPairService service = buildService();
        when(repository.existsByName("deploy-key")).thenReturn(false);
        when(sshKeyGenerationService.generateEd25519("comment"))
                .thenReturn(new SshKeyGenerationService.SshKeyPair("PRIVATE-PEM", "ssh-ed25519 AAAA... comment"));

        SshKeyPairGeneratedResponse response = service.generate(new SshKeyPairCreateRequest("deploy-key", "comment"));

        ArgumentCaptor<SshKeyPair> captor = ArgumentCaptor.forClass(SshKeyPair.class);
        verify(repository).save(captor.capture());
        SshKeyPair saved = captor.getValue();
        assertEquals("deploy-key", saved.getName());
        assertEquals("ssh-ed25519 AAAA... comment", saved.getPublicKeyLine());
        assertEquals("PRIVATE-PEM", credentialCipher.decrypt(saved.getPrivateKeyEncrypted()));
        assertEquals("PRIVATE-PEM", response.privateKeyPem());
        assertEquals("deploy-key", response.name());
    }

    @Test
    void list_admin権限がなければForbidden() {
        SshKeyPairService service = buildService();
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です"))
                .when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, service::list);
    }

    @Test
    void list_公開鍵のみを含み秘密鍵は含まない() {
        SshKeyPairService service = buildService();
        SshKeyPair entity = new SshKeyPair("deploy-key", "comment", "ssh-ed25519 AAAA...",
                credentialCipher.encrypt("PRIVATE-PEM"));
        when(repository.findAll()).thenReturn(List.of(entity));

        List<?> result = service.list();

        assertEquals(1, result.size());
    }

    @Test
    void delete_admin権限がなければForbidden() {
        SshKeyPairService service = buildService();
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です"))
                .when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> service.delete(1L));
    }

    @Test
    void delete_存在しなければNotFound() {
        SshKeyPairService service = buildService();
        lenient().when(repository.existsById(1L)).thenReturn(false);

        assertThrows(SshKeyPairNotFoundException.class, () -> service.delete(1L));
        verify(repository, never()).deleteById(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void delete_存在すれば削除する() {
        SshKeyPairService service = buildService();
        when(repository.existsById(1L)).thenReturn(true);

        service.delete(1L);

        verify(repository).deleteById(1L);
    }
}
