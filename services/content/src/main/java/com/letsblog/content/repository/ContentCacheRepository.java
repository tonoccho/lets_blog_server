package com.letsblog.content.repository;

import com.letsblog.content.domain.ContentCache;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ContentCacheRepository extends JpaRepository<ContentCache, Long> {
    Optional<ContentCache> findByUrlHash(String urlHash);
}
