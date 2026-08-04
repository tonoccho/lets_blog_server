package com.letsblog.api.repository;

import com.letsblog.api.domain.GeneratedImage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GeneratedImageRepository extends JpaRepository<GeneratedImage, Long> {
    List<GeneratedImage> findAllByOrderByCreatedAtDesc();
    List<GeneratedImage> findAllByProjectIdOrderByCreatedAtDesc(Long projectId);
}
