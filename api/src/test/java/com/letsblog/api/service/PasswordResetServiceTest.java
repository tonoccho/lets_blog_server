package com.letsblog.api.service;

import com.letsblog.api.domain.PasswordResetToken;
import com.letsblog.api.domain.User;
import com.letsblog.api.repository.PasswordResetTokenRepository;
import com.letsblog.api.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

    @Mock
    private PasswordResetTokenRepository tokenRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private MailSenderService mailSenderService;

    @Mock
    private AppSettingService appSettingService;

    private PasswordResetService service;

    @BeforeEach
    void setUp() {
        service = new PasswordResetService(tokenRepository, userRepository, mailSenderService, appSettingService);
        org.mockito.Mockito.lenient().when(appSettingService.getAppWebBaseUrl()).thenReturn("http://localhost:3000");
    }

    private User buildUser() {
        User user = new User();
        user.setId(1L);
        user.setEmail("user@example.com");
        user.setPasswordHash("old-hash");
        user.setRole("user");
        return user;
    }

    @Test
    void requestPasswordReset_未登録メールの場合は何もしない() {
        when(userRepository.findByEmail("unknown@example.com")).thenReturn(Optional.empty());

        service.requestPasswordReset("unknown@example.com");

        verify(tokenRepository, never()).save(any());
        verify(mailSenderService, never()).sendMail(any(), any(), any());
    }

    @Test
    void requestPasswordReset_登録済みメールの場合はトークンを生成しメール送信する() {
        User user = buildUser();
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(tokenRepository.findByUserIdAndUsedFalse(1L)).thenReturn(Optional.empty());

        service.requestPasswordReset("user@example.com");

        ArgumentCaptor<PasswordResetToken> tokenCaptor = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(tokenRepository, times(1)).save(tokenCaptor.capture());
        assertEquals(1L, tokenCaptor.getValue().getUserId());
        assertFalse(tokenCaptor.getValue().getUsed());

        verify(mailSenderService, times(1))
                .sendMail(eq("user@example.com"), eq("password-reset"), any(Map.class));
    }

    @Test
    void requestPasswordReset_既存の未使用トークンは無効化してから新規発行する() {
        User user = buildUser();
        PasswordResetToken existing = new PasswordResetToken();
        existing.setToken("old-token");
        existing.setUserId(1L);
        existing.setUsed(false);

        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(tokenRepository.findByUserIdAndUsedFalse(1L)).thenReturn(Optional.of(existing));

        service.requestPasswordReset("user@example.com");

        ArgumentCaptor<PasswordResetToken> tokenCaptor = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(tokenRepository, times(2)).save(tokenCaptor.capture());
        assertTrue(tokenCaptor.getAllValues().get(0).getUsed(), "既存トークンは無効化されるべき");
    }

    @Test
    void confirmPasswordReset_正常系でパスワードが更新されトークンが無効化される() {
        User user = buildUser();
        PasswordResetToken token = new PasswordResetToken();
        token.setToken("valid-token");
        token.setUserId(1L);
        token.setUsed(false);
        token.setExpiresAt(LocalDateTime.now().plusHours(1));

        when(tokenRepository.findByToken("valid-token")).thenReturn(Optional.of(token));
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        service.confirmPasswordReset("valid-token", "newPassword123");

        verify(userRepository, times(1)).save(user);
        assertTrue(token.getUsed());
        verify(tokenRepository, times(1)).save(token);
    }

    @Test
    void confirmPasswordReset_無効なトークンは例外() {
        when(tokenRepository.findByToken("invalid")).thenReturn(Optional.empty());

        assertThrows(InvalidTokenException.class,
                () -> service.confirmPasswordReset("invalid", "newPassword123"));
    }

    @Test
    void confirmPasswordReset_使用済みトークンは例外() {
        PasswordResetToken token = new PasswordResetToken();
        token.setToken("used-token");
        token.setUsed(true);
        token.setExpiresAt(LocalDateTime.now().plusHours(1));

        when(tokenRepository.findByToken("used-token")).thenReturn(Optional.of(token));

        assertThrows(InvalidTokenException.class,
                () -> service.confirmPasswordReset("used-token", "newPassword123"));
    }

    @Test
    void confirmPasswordReset_期限切れトークンは例外() {
        PasswordResetToken token = new PasswordResetToken();
        token.setToken("expired-token");
        token.setUsed(false);
        token.setExpiresAt(LocalDateTime.now().minusHours(1));

        when(tokenRepository.findByToken("expired-token")).thenReturn(Optional.of(token));

        assertThrows(InvalidTokenException.class,
                () -> service.confirmPasswordReset("expired-token", "newPassword123"));
    }

    @Test
    void requestPasswordReset_メール送信失敗時は例外を投げる() {
        User user = buildUser();
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(tokenRepository.findByUserIdAndUsedFalse(1L)).thenReturn(Optional.empty());
        doThrow(new EmailSendException("メール送信に失敗しました", new RuntimeException("smtp down")))
                .when(mailSenderService).sendMail(any(), any(), any());

        assertThrows(EmailSendException.class,
                () -> service.requestPasswordReset("user@example.com"));
    }
}
