package com.letsblog.api.repository;

import com.letsblog.api.domain.Site;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SiteRepository extends JpaRepository<Site, Long> {
    Optional<Site> findBySiteKey(String siteKey);
    boolean existsBySiteKey(String siteKey);
}
