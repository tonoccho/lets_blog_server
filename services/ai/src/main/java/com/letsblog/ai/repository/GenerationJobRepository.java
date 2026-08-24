package com.letsblog.ai.repository;

import com.letsblog.ai.domain.GenerationJob;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GenerationJobRepository extends JpaRepository<GenerationJob, Long> {
    Page<GenerationJob> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
