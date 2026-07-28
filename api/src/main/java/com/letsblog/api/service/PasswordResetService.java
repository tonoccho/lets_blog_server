package com.letsblog.api.service;

import com.letsblog.api.aop.AuditLog;
import com.letsblog.api.domain.AuditLogAction;
import com.letsblog.api.domain.PasswordResetToken;
import com.letsblog.api.domain.User;
import com.letsblog.api.repository.PasswordResetTokenRepository;
import com.letsblog.api.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
public class PasswordResetService {

    private static final int TOKEN_EXPIRY_HOURS = 24;

    private final PasswordResetTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final MailSenderService mailSenderService;

    @Value("${app.web.base-url:http://localhost:3000}")
    private String baseUrl;

    public PasswordResetService(
            PasswordResetTokenRepository tokenRepository,
            UserRepository userRepository,
            MailSenderService mailSenderService) {
        this.tokenRepository = tokenRepository;
        this.userRepository = userRepository;
        this.mailSenderService = mailSenderService;
    }

    /**
     * パスワード再設定リクエストを処理し、再設定用メールを送信する。
     * メールアドレスが未登録の場合も例外にせず何もしない
     * (登録有無を応答から推測されないようにするため。呼び出し元は常に成功メッセージを返す)。
     */
    @AuditLog(action = AuditLogAction.PASSWORD_RESET_REQUESTED, resourceType = "USER")
    @Transactional
    public void requestPasswordReset(String email) {
        userRepository.findByEmail(email).ifPresent(user -> {
            tokenRepository.findByUserIdAndUsedFalse(user.getId())
                    .ifPresent(existingToken -> {
                        existingToken.setUsed(true);
                        tokenRepository.save(existingToken);
                    });

            String token = UUID.randomUUID().toString();
            PasswordResetToken resetToken = new PasswordResetToken();
            resetToken.setToken(token);
            resetToken.setUserId(user.getId());
            resetToken.setExpiresAt(LocalDateTime.now().plusHours(TOKEN_EXPIRY_HOURS));
            resetToken.setUsed(false);

            tokenRepository.save(resetToken);

            sendResetEmail(user.getEmail(), token);
            log.info("Password reset token generated for user: {}", user.getId());
        });
    }

    /**
     * トークンを検証して、新しいパスワードを設定する。
     */
    @AuditLog(action = AuditLogAction.PASSWORD_RESET_CONFIRMED, resourceType = "USER")
    @Transactional
    public void confirmPasswordReset(String token, String newPassword) {
        PasswordResetToken resetToken = tokenRepository.findByToken(token)
                .orElseThrow(() -> new InvalidTokenException("無効な再設定トークンです"));

        if (resetToken.getUsed()) {
            throw new InvalidTokenException("このトークンは既に使用済みです");
        }

        if (LocalDateTime.now().isAfter(resetToken.getExpiresAt())) {
            throw new InvalidTokenException("再設定トークンの有効期限が切れています");
        }

        User user = userRepository.findById(resetToken.getUserId())
                .orElseThrow(() -> new UserNotFoundException("ユーザーが見つかりません"));

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        resetToken.setUsed(true);
        tokenRepository.save(resetToken);

        log.info("Password reset confirmed for user: {}", user.getId());
    }

    private void sendResetEmail(String email, String token) {
        String resetLink = baseUrl + "/login/password-reset?token=" + token;
        mailSenderService.sendMail(email, "password-reset", Map.of("resetLink", resetLink));
        log.info("Reset email sent to: {}", email);
    }
}
