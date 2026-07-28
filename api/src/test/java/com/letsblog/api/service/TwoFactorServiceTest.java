package com.letsblog.api.service;

import com.letsblog.api.domain.TwoFactorSecret;
import com.letsblog.api.dto.TwoFactorSetupResponse;
import com.letsblog.api.repository.TwoFactorSecretRepository;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TwoFactorServiceTest {

    @Mock
    private TwoFactorSecretRepository twoFactorSecretRepository;

    private TwoFactorService service;

    @BeforeEach
    void setUp() {
        service = new TwoFactorService(twoFactorSecretRepository);
    }

    private String currentValidCode(String secret) throws Exception {
        return new DefaultCodeGenerator().generate(secret, new SystemTimeProvider().getTime() / 30);
    }

    @Test
    void generateTwoFactorSecret_QRコードとバックアップコード10個を返す() {
        when(twoFactorSecretRepository.findByUserId(1L)).thenReturn(Optional.empty());
        when(twoFactorSecretRepository.save(any(TwoFactorSecret.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        TwoFactorSetupResponse response = service.generateTwoFactorSecret(1L, "user@example.com");

        assertTrue(response.qrCodeDataUrl().startsWith("data:image/png;base64,"));
        assertEquals(10, response.backupCodes().size());

        ArgumentCaptor<TwoFactorSecret> captor = ArgumentCaptor.forClass(TwoFactorSecret.class);
        verify(twoFactorSecretRepository, times(1)).save(captor.capture());
        assertEquals(1L, captor.getValue().getUserId());
        assertFalse(captor.getValue().getIsEnabled());
    }

    @Test
    void generateTwoFactorSecret_既に有効化済みなら例外() {
        TwoFactorSecret existing = new TwoFactorSecret();
        existing.setIsEnabled(true);
        when(twoFactorSecretRepository.findByUserId(1L)).thenReturn(Optional.of(existing));

        assertThrows(IllegalStateException.class,
                () -> service.generateTwoFactorSecret(1L, "user@example.com"));
    }

    @Test
    void generateTwoFactorSecret_未有効化の既存シークレットは削除してから再生成する() {
        TwoFactorSecret existing = new TwoFactorSecret();
        existing.setIsEnabled(false);
        when(twoFactorSecretRepository.findByUserId(1L)).thenReturn(Optional.of(existing));
        when(twoFactorSecretRepository.save(any(TwoFactorSecret.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.generateTwoFactorSecret(1L, "user@example.com");

        verify(twoFactorSecretRepository, times(1)).delete(existing);
    }

    @Test
    void verifyAndEnableTwoFactor_正しいコードで有効化される() throws Exception {
        String secret = new DefaultSecretGenerator().generate();
        TwoFactorSecret entity = new TwoFactorSecret();
        entity.setUserId(1L);
        entity.setSecret(secret);
        entity.setIsEnabled(false);
        when(twoFactorSecretRepository.findByUserId(1L)).thenReturn(Optional.of(entity));

        service.verifyAndEnableTwoFactor(1L, currentValidCode(secret));

        assertTrue(entity.getIsEnabled());
        verify(twoFactorSecretRepository, times(1)).save(entity);
    }

    @Test
    void verifyAndEnableTwoFactor_誤ったコードは例外() {
        TwoFactorSecret entity = new TwoFactorSecret();
        entity.setUserId(1L);
        entity.setSecret(new DefaultSecretGenerator().generate());
        entity.setIsEnabled(false);
        when(twoFactorSecretRepository.findByUserId(1L)).thenReturn(Optional.of(entity));

        assertThrows(InvalidTotpCodeException.class,
                () -> service.verifyAndEnableTwoFactor(1L, "000000"));
    }

    @Test
    void verifyAndEnableTwoFactor_シークレット未生成は例外() {
        when(twoFactorSecretRepository.findByUserId(1L)).thenReturn(Optional.empty());

        assertThrows(TwoFactorSecretNotFoundException.class,
                () -> service.verifyAndEnableTwoFactor(1L, "123456"));
    }

    @Test
    void verifyTotpCode_2FA未有効ならfalse() {
        when(twoFactorSecretRepository.findByUserIdAndIsEnabledTrue(1L)).thenReturn(Optional.empty());

        assertFalse(service.verifyTotpCode(1L, "123456"));
    }

    @Test
    void verifyTotpCode_正しいTOTPコードでtrue() throws Exception {
        String secret = new DefaultSecretGenerator().generate();
        TwoFactorSecret entity = new TwoFactorSecret();
        entity.setUserId(1L);
        entity.setSecret(secret);
        entity.setIsEnabled(true);
        entity.setBackupCodes("[]");
        when(twoFactorSecretRepository.findByUserIdAndIsEnabledTrue(1L)).thenReturn(Optional.of(entity));

        assertTrue(service.verifyTotpCode(1L, currentValidCode(secret)));
    }

    @Test
    void verifyTotpCode_バックアップコードでも認証でき消費される() {
        TwoFactorSecret entity = new TwoFactorSecret();
        entity.setUserId(1L);
        entity.setSecret(new DefaultSecretGenerator().generate());
        entity.setIsEnabled(true);
        entity.setBackupCodes("[\"12345678\",\"87654321\"]");
        when(twoFactorSecretRepository.findByUserIdAndIsEnabledTrue(1L)).thenReturn(Optional.of(entity));

        assertTrue(service.verifyTotpCode(1L, "12345678"));

        assertFalse(entity.getBackupCodes().contains("12345678"), "使用済みバックアップコードは消費されるべき");
        verify(twoFactorSecretRepository, times(1)).save(entity);
    }

    @Test
    void verifyTotpCode_誤ったコードはfalse() {
        TwoFactorSecret entity = new TwoFactorSecret();
        entity.setUserId(1L);
        entity.setSecret(new DefaultSecretGenerator().generate());
        entity.setIsEnabled(true);
        entity.setBackupCodes("[]");
        when(twoFactorSecretRepository.findByUserIdAndIsEnabledTrue(1L)).thenReturn(Optional.of(entity));

        assertFalse(service.verifyTotpCode(1L, "000000"));
    }

    @Test
    void isTwoFactorEnabled_有効なシークレットがあればtrue() {
        when(twoFactorSecretRepository.findByUserIdAndIsEnabledTrue(1L))
                .thenReturn(Optional.of(new TwoFactorSecret()));

        assertTrue(service.isTwoFactorEnabled(1L));
    }

    @Test
    void disableTwoFactor_存在すれば削除する() {
        TwoFactorSecret entity = new TwoFactorSecret();
        when(twoFactorSecretRepository.findByUserId(1L)).thenReturn(Optional.of(entity));

        service.disableTwoFactor(1L);

        verify(twoFactorSecretRepository, times(1)).delete(entity);
    }
}
