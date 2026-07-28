package com.letsblog.api.repository;

import com.letsblog.api.domain.CustomTag;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CustomTagRepository extends JpaRepository<CustomTag, Long> {
    Optional<CustomTag> findByTagName(String tagName);

    boolean existsByTagName(String tagName);
}
