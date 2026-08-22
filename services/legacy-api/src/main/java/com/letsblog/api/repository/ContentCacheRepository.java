package com.letsblog.api.repository;

import com.letsblog.api.domain.ContentCache;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ContentCacheRepository extends JpaRepository<ContentCache, Long> {
    Optional<ContentCache> findByUrlHash(String urlHash);
}
