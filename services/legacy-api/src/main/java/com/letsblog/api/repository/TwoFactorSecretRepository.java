package com.letsblog.api.repository;

import com.letsblog.api.domain.TwoFactorSecret;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TwoFactorSecretRepository extends JpaRepository<TwoFactorSecret, Long> {
    Optional<TwoFactorSecret> findByUserId(Long userId);
    Optional<TwoFactorSecret> findByUserIdAndIsEnabledTrue(Long userId);
}
