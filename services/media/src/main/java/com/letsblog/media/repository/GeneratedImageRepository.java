package com.letsblog.media.repository;

import com.letsblog.media.domain.GeneratedImage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GeneratedImageRepository extends JpaRepository<GeneratedImage, Long> {
    List<GeneratedImage> findAllByOrderByCreatedAtDesc();
    List<GeneratedImage> findAllByProjectIdOrderByCreatedAtDesc(Long projectId);
}
