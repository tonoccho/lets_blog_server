package com.letsblog.project.repository;

import com.letsblog.project.domain.StaticContent;
import com.letsblog.project.domain.StaticContentType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StaticContentRepository extends JpaRepository<StaticContent, Long> {
    List<StaticContent> findBySiteId(Long siteId);

    Optional<StaticContent> findBySiteIdAndContentType(Long siteId, StaticContentType contentType);
}
