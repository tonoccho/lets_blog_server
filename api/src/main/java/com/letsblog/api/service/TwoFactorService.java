package com.letsblog.api.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.api.domain.TwoFactorSecret;
import com.letsblog.api.dto.TwoFactorSetupResponse;
import com.letsblog.api.repository.TwoFactorSecretRepository;
import dev.samstevens.totp.code.CodeVerifier;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.DefaultCodeVerifier;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.exceptions.QrGenerationException;
import dev.samstevens.totp.qr.QrData;
import dev.samstevens.totp.qr.QrGenerator;
import dev.samstevens.totp.qr.ZxingPngQrGenerator;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class TwoFactorService {

    private static final int BACKUP_CODE_COUNT = 10;
    private static final String ISSUER = "Let's Blog";

    private final TwoFactorSecretRepository twoFactorSecretRepository;
    private final QrGenerator qrGenerator;
    private final CodeVerifier codeVerifier;
    private final ObjectMapper objectMapper;
    private final SecureRandom secureRandom = new SecureRandom();

    public TwoFactorService(TwoFactorSecretRepository twoFactorSecretRepository) {
        this.twoFactorSecretRepository = twoFactorSecretRepository;
        this.qrGenerator = new ZxingPngQrGenerator();
        this.codeVerifier = new DefaultCodeVerifier(new DefaultCodeGenerator(), new SystemTimeProvider());
        this.objectMapper = new ObjectMapper();
    }

    /**
     * TOTPシークレットを生成し、QRコード(Base64 Data URL)とバックアップコードを返す。
     * 未有効化のシークレットが既にある場合は、削除→再作成ではなく既存行を上書きする
     * (同一トランザクション内でdelete+insertを行うと、Hibernateのフラッシュ順序が
     * 常にinsertを先に実行するため、user_idのUNIQUE制約に違反してしまう)。
     */
    @Transactional
    public TwoFactorSetupResponse generateTwoFactorSecret(Long userId, String userEmail) {
        TwoFactorSecret twoFactorSecret = twoFactorSecretRepository.findByUserId(userId)
                .map(existing -> {
                    if (existing.getIsEnabled()) {
                        throw new IllegalStateException("ユーザーは既に2FAが有効化されています");
                    }
                    return existing;
                })
                .orElseGet(TwoFactorSecret::new);

        String secret = new DefaultSecretGenerator().generate();
        List<String> backupCodes = generateBackupCodes();

        twoFactorSecret.setUserId(userId);
        twoFactorSecret.setSecret(secret);
        twoFactorSecret.setIsEnabled(false);
        twoFactorSecret.setEnabledAt(null);
        twoFactorSecret.setBackupCodes(serializeBackupCodes(backupCodes));

        twoFactorSecretRepository.save(twoFactorSecret);

        String qrCodeDataUrl = generateQrCodeDataUrl(secret, userEmail);
        return new TwoFactorSetupResponse(qrCodeDataUrl, backupCodes);
    }

    private String generateQrCodeDataUrl(String secret, String userEmail) {
        try {
            QrData data = new QrData.Builder()
                    .label(userEmail)
                    .secret(secret)
                    .issuer(ISSUER)
                    .algorithm(HashingAlgorithm.SHA1)
                    .digits(6)
                    .period(30)
                    .build();

            byte[] imageData = qrGenerator.generate(data);
            String base64 = java.util.Base64.getEncoder().encodeToString(imageData);
            return "data:" + qrGenerator.getImageMimeType() + ";base64," + base64;
        } catch (QrGenerationException e) {
            log.error("Failed to generate QR code: {}", e.getMessage());
            throw new QrCodeGenerationException("QRコード生成に失敗しました", e);
        }
    }

    /**
     * ユーザー入力のTOTPコードを検証し、2FAを有効化する。
     */
    @Transactional
    public void verifyAndEnableTwoFactor(Long userId, String code) {
        TwoFactorSecret twoFactorSecret = twoFactorSecretRepository.findByUserId(userId)
                .orElseThrow(() -> new TwoFactorSecretNotFoundException("2FAシークレットが見つかりません"));

        if (twoFactorSecret.getIsEnabled()) {
            throw new IllegalStateException("2FAは既に有効化されています");
        }

        if (!codeVerifier.isValidCode(twoFactorSecret.getSecret(), code)) {
            throw new InvalidTotpCodeException("無効なTOTPコードです。正しいコードを入力してください。");
        }

        twoFactorSecret.setIsEnabled(true);
        twoFactorSecret.setEnabledAt(LocalDateTime.now());
        twoFactorSecretRepository.save(twoFactorSecret);

        log.info("2FA enabled for user: {}", userId);
    }

    /**
     * ログイン時に入力されたTOTPコード(またはバックアップコード)を検証する。
     * 2FAが有効化されていないユーザーの場合はfalseを返す。
     */
    @Transactional
    public boolean verifyTotpCode(Long userId, String code) {
        TwoFactorSecret twoFactorSecret = twoFactorSecretRepository.findByUserIdAndIsEnabledTrue(userId)
                .orElse(null);

        if (twoFactorSecret == null) {
            return false;
        }

        if (isValidBackupCode(twoFactorSecret, code)) {
            consumeBackupCode(twoFactorSecret, code);
            return true;
        }

        return codeVerifier.isValidCode(twoFactorSecret.getSecret(), code);
    }

    /**
     * 2FAを無効化する(ユーザー自身または管理画面から)。
     */
    @Transactional
    public void disableTwoFactor(Long userId) {
        twoFactorSecretRepository.findByUserId(userId)
                .ifPresent(secret -> {
                    twoFactorSecretRepository.delete(secret);
                    log.info("2FA disabled for user: {}", userId);
                });
    }

    @Transactional(readOnly = true)
    public boolean isTwoFactorEnabled(Long userId) {
        return twoFactorSecretRepository.findByUserIdAndIsEnabledTrue(userId).isPresent();
    }

    private boolean isValidBackupCode(TwoFactorSecret twoFactorSecret, String code) {
        return deserializeBackupCodes(twoFactorSecret.getBackupCodes()).contains(code);
    }

    private void consumeBackupCode(TwoFactorSecret twoFactorSecret, String code) {
        List<String> backupCodes = deserializeBackupCodes(twoFactorSecret.getBackupCodes());
        backupCodes.remove(code);
        twoFactorSecret.setBackupCodes(serializeBackupCodes(backupCodes));
        twoFactorSecretRepository.save(twoFactorSecret);
        log.info("Backup code consumed for user: {}", twoFactorSecret.getUserId());
    }

    private List<String> generateBackupCodes() {
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < BACKUP_CODE_COUNT; i++) {
            codes.add(String.format("%08d", secureRandom.nextInt(100_000_000)));
        }
        return codes;
    }

    private String serializeBackupCodes(List<String> backupCodes) {
        try {
            return objectMapper.writeValueAsString(backupCodes);
        } catch (Exception e) {
            throw new IllegalStateException("バックアップコードのシリアライズに失敗しました", e);
        }
    }

    private List<String> deserializeBackupCodes(String json) {
        try {
            return new ArrayList<>(objectMapper.readValue(json, new TypeReference<List<String>>() {}));
        } catch (Exception e) {
            log.error("Failed to parse backup codes: {}", e.getMessage());
            return new ArrayList<>();
        }
    }
}
