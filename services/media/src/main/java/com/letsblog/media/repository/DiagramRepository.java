package com.letsblog.media.repository;

import com.letsblog.media.domain.Diagram;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DiagramRepository extends JpaRepository<Diagram, Long> {
    List<Diagram> findAllByOrderByCreatedAtDesc();

    List<Diagram> findAllByProjectIdOrderByCreatedAtDesc(Long projectId);
}
