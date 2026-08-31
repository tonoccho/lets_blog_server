package com.letsblog.api.service;

import com.letsblog.api.domain.ApiKey;
import com.letsblog.api.domain.User;
import com.letsblog.api.repository.ApiKeyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;

/**
 * ユーザー単位のAPIキーの発行・検証を行う。キーは高エントロピーなランダム値のため、
 * パスワードのような低速ハッシュ(BCrypt)は不要でSHA-256で十分。平文はDBに保存せず、
 * 発行時のレスポンスとしてのみクライアントに一度だけ返す。
 */
@Service
public class ApiKeyService {

    private static final String KEY_PREFIX = "lb_";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final ApiKeyRepository apiKeyRepository;

    public ApiKeyService(ApiKeyRepository apiKeyRepository) {
        this.apiKeyRepository = apiKeyRepository;
    }

    @Transactional
    public String issue(User user, String label) {
        byte[] randomBytes = new byte[24];
        SECURE_RANDOM.nextBytes(randomBytes);
        String rawKey = KEY_PREFIX + HexFormat.of().formatHex(randomBytes);

        ApiKey apiKey = new ApiKey();
        apiKey.setUserId(user.getId());
        apiKey.setKeyHash(hash(rawKey));
        apiKey.setKeyPrefix(rawKey.substring(0, Math.min(rawKey.length(), 10)));
        apiKey.setLabel(label == null || label.isBlank() ? "unknown" : label);
        apiKeyRepository.save(apiKey);

        return rawKey;
    }

    @Transactional
    public Optional<Long> resolveUserId(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            return Optional.empty();
        }
        Optional<ApiKey> found = apiKeyRepository.findByKeyHashAndRevokedAtIsNull(hash(rawKey));
        found.ifPresent(apiKey -> {
            apiKey.setLastUsedAt(LocalDateTime.now());
            apiKeyRepository.save(apiKey);
        });
        return found.map(ApiKey::getUserId);
    }

    private String hash(String rawKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(rawKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256アルゴリズムが利用できません", e);
        }
    }
}
