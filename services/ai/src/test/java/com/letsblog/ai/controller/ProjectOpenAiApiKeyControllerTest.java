package com.letsblog.ai.controller;

import com.letsblog.ai.dto.ProjectApiKeyStatusResponse;
import com.letsblog.ai.dto.SetProjectOpenAiApiKeyRequest;
import com.letsblog.ai.service.AdminAuthorizationService;
import com.letsblog.ai.service.ForbiddenException;
import com.letsblog.ai.service.ProjectAiSettingsService;
import com.letsblog.common.crypto.CredentialCipher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** ProjectOpenAiApiKeyController(issue #1506)。認可は requireProjectMemberOrAdmin、キーは暗号化して保存する。 */
@ExtendWith(MockitoExtension.class)
class ProjectOpenAiApiKeyControllerTest {

    @Mock
    private ProjectAiSettingsService settingsService;
    @Mock
    private CredentialCipher cipher;
    @Mock
    private AdminAuthorizationService authorization;

    private ProjectOpenAiApiKeyController controller() {
        return new ProjectOpenAiApiKeyController(settingsService, cipher, authorization);
    }

    @Test
    void status_メンバー判定後に設定有無だけを返す() {
        when(settingsService.hasOpenAiApiKey(7L)).thenReturn(true, false);

        assertTrue(controller().status(7L).configured());
        ProjectApiKeyStatusResponse second = controller().status(7L);
        assertFalse(second.configured());

        verify(authorization, org.mockito.Mockito.times(2)).requireProjectMemberOrAdmin(7L);
    }

    @Test
    void set_暗号化して保存し204を返す() {
        byte[] encrypted = {4, 5};
        when(cipher.encrypt("sk-abc")).thenReturn(encrypted);

        assertEquals(204, controller().set(7L, new SetProjectOpenAiApiKeyRequest("sk-abc")).getStatusCode().value());

        verify(authorization).requireProjectMemberOrAdmin(7L);
        verify(settingsService).setOpenAiApiKeyEncrypted(7L, encrypted);
    }

    @Test
    void clear_nullを保存して削除し204を返す() {
        assertEquals(204, controller().clear(7L).getStatusCode().value());

        verify(authorization).requireProjectMemberOrAdmin(7L);
        verify(settingsService).setOpenAiApiKeyEncrypted(7L, null);
    }

    @Test
    void メンバーでなければForbiddenで何も保存しない() {
        doThrow(new ForbiddenException("拒否")).when(authorization).requireProjectMemberOrAdmin(7L);

        assertThrows(ForbiddenException.class, () -> controller().status(7L));
        assertThrows(ForbiddenException.class,
                () -> controller().set(7L, new SetProjectOpenAiApiKeyRequest("sk")));
        assertThrows(ForbiddenException.class, () -> controller().clear(7L));

        verifyNoInteractions(settingsService, cipher);
    }
}
