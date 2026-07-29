package com.letsblog.api.repository;

import com.letsblog.api.domain.CustomTag;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CustomTagRepository extends JpaRepository<CustomTag, Long> {
    Optional<CustomTag> findByTagName(String tagName);

    boolean existsByTagName(String tagName);

    Optional<CustomTag> findByTagNameAndProjectId(String tagName, Long projectId);

    Optional<CustomTag> findByTagNameAndProjectIdIsNull(String tagName);

    List<CustomTag> findByProjectIdIsNull();

    List<CustomTag> findByProjectIdOrProjectIdIsNull(Long projectId);
}
