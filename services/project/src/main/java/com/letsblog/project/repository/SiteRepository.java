package com.letsblog.project.repository;

import com.letsblog.project.domain.Site;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SiteRepository extends JpaRepository<Site, Long> {
    Optional<Site> findBySiteKey(String siteKey);

    boolean existsBySiteKey(String siteKey);
}
