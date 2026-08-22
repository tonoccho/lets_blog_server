package com.letsblog.api.repository;

import com.letsblog.api.domain.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ApiKeyRepository extends JpaRepository<ApiKey, Long> {
    Optional<ApiKey> findByKeyHashAndRevokedAtIsNull(String keyHash);
}
