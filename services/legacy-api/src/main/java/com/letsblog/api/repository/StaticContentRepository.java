package com.letsblog.api.repository;

import com.letsblog.api.domain.StaticContent;
import com.letsblog.api.domain.StaticContentType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StaticContentRepository extends JpaRepository<StaticContent, Long> {
    List<StaticContent> findBySiteId(Long siteId);

    Optional<StaticContent> findBySiteIdAndContentType(Long siteId, StaticContentType contentType);
}
